package com.novelreader.tts.android.store

import android.content.Context
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.store.CastStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File-based JSON implementation of [CastStore] storing one file per book in app storage.
 *
 * Saves are performed atomically via temp file write followed by rename.
 * Corrupt or empty files safely fall back to default characters ("narrator" and "unnamed")
 * without crashing. Supports per-book ignored names list to prevent deleted auto-detected
 * characters from reappearing on reload.
 */
class JsonFileCastStore(
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CastStore {

    /**
     * Secondary constructor for standard Android context usage.
     */
    constructor(context: Context) : this(File(context.filesDir, "cast"))

    private val mutex = Mutex()
    private val inMemoryCharactersFlows = mutableMapOf<String, MutableStateFlow<List<Character>>>()
    private val loadedBookData = mutableMapOf<String, BookData>()

    private data class BookData(
        val characters: MutableMap<String, Character>,
        val overrides: MutableMap<String, MutableMap<String, String>>, // chapterId -> (segId -> charId)
        val ignoredNames: MutableSet<String> = mutableSetOf()
    )

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    private fun getFileForBook(bookId: String): File {
        val safeId = bookId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(baseDir, "$safeId.json")
    }

    private fun ensureBuiltInsExist(map: MutableMap<String, Character>) {
        if (!map.containsKey(Character.NARRATOR_ID)) {
            map[Character.NARRATOR_ID] = Character.Narrator
        }
        if (!map.containsKey(Character.UNNAMED_ID)) {
            map[Character.UNNAMED_ID] = Character.Unnamed
        }
    }

    private fun loadOrCreateBookData(bookId: String): BookData {
        val cached = loadedBookData[bookId]
        if (cached != null) return cached

        val file = getFileForBook(bookId)
        val data = if (file.exists() && file.isFile && file.length() > 0) {
            try {
                val content = file.readText(Charsets.UTF_8).trim()
                if (content.isEmpty()) {
                    createDefaultBookData()
                } else {
                    parseBookDataJson(content) ?: createDefaultBookData()
                }
            } catch (_: Exception) {
                createDefaultBookData()
            }
        } else {
            createDefaultBookData()
        }

        ensureBuiltInsExist(data.characters)
        loadedBookData[bookId] = data
        return data
    }

    private fun createDefaultBookData(): BookData {
        val chars = linkedMapOf<String, Character>()
        chars[Character.NARRATOR_ID] = Character.Narrator
        chars[Character.UNNAMED_ID] = Character.Unnamed
        return BookData(
            characters = chars,
            overrides = mutableMapOf(),
            ignoredNames = mutableSetOf()
        )
    }

    private fun getOrCreateFlowLocked(bookId: String): MutableStateFlow<List<Character>> {
        return inMemoryCharactersFlows.getOrPut(bookId) {
            val bookData = loadOrCreateBookData(bookId)
            MutableStateFlow(bookData.characters.values.toList())
        }
    }

    override fun characters(bookId: String): Flow<List<Character>> {
        val flow = synchronized(inMemoryCharactersFlows) {
            inMemoryCharactersFlows.getOrPut(bookId) {
                val bookData = try {
                    loadOrCreateBookData(bookId)
                } catch (_: Exception) {
                    createDefaultBookData()
                }
                MutableStateFlow(bookData.characters.values.toList())
            }
        }
        return flow.asStateFlow()
    }

    override suspend fun saveCharacter(bookId: String, character: Character): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val bookData = loadOrCreateBookData(bookId)
            bookData.characters[character.id] = character
            ensureBuiltInsExist(bookData.characters)

            // Re-adding or updating a character unignores its name and aliases
            bookData.ignoredNames.remove(character.name.trim().lowercase())
            character.aliases.forEach { bookData.ignoredNames.remove(it.trim().lowercase()) }

            saveBookDataToDisk(bookId, bookData)

            val flow = getOrCreateFlowLocked(bookId)
            flow.value = bookData.characters.values.toList()
        }
    }

    override suspend fun deleteCharacter(bookId: String, characterId: String): Unit = withContext(ioDispatcher) {
        if (Character.isBuiltIn(characterId)) {
            // Built-ins cannot be deleted
            return@withContext
        }

        mutex.withLock {
            val bookData = loadOrCreateBookData(bookId)
            val charToDelete = bookData.characters[characterId] ?: return@withLock

            // Add deleted character name and its aliases to ignoredNames so it won't be recreated on reload
            bookData.ignoredNames.add(charToDelete.name.trim().lowercase())
            charToDelete.aliases.forEach { bookData.ignoredNames.add(it.trim().lowercase()) }

            bookData.characters.remove(characterId)
            ensureBuiltInsExist(bookData.characters)

            // Revert lines: remove any overrides in this book pointing to the deleted character
            bookData.overrides.values.forEach { chapterMap ->
                val iterator = chapterMap.entries.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value == characterId) {
                        iterator.remove()
                    }
                }
            }

            saveBookDataToDisk(bookId, bookData)

            val flow = getOrCreateFlowLocked(bookId)
            flow.value = bookData.characters.values.toList()
        }
    }

    override suspend fun setOverride(
        bookId: String,
        chapterId: String,
        segmentId: String,
        characterId: String?
    ): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val bookData = loadOrCreateBookData(bookId)
            val chapterOverrides = bookData.overrides.getOrPut(chapterId) { mutableMapOf() }
            if (characterId == null) {
                chapterOverrides.remove(segmentId)
            } else {
                chapterOverrides[segmentId] = characterId
            }

            saveBookDataToDisk(bookId, bookData)
        }
    }

    override suspend fun overrides(bookId: String, chapterId: String): Map<String, String> = withContext(ioDispatcher) {
        mutex.withLock {
            val bookData = loadOrCreateBookData(bookId)
            bookData.overrides[chapterId]?.toMap() ?: emptyMap()
        }
    }

    override suspend fun ignoredNames(bookId: String): Set<String> = withContext(ioDispatcher) {
        mutex.withLock {
            val bookData = loadOrCreateBookData(bookId)
            bookData.ignoredNames.toSet()
        }
    }

    override suspend fun unignoreName(bookId: String, name: String): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val bookData = loadOrCreateBookData(bookId)
            bookData.ignoredNames.remove(name.trim().lowercase())
            saveBookDataToDisk(bookId, bookData)
        }
    }

    private fun saveBookDataToDisk(bookId: String, data: BookData) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val targetFile = getFileForBook(bookId)
        val tempFile = File(baseDir, "${targetFile.name}.tmp")

        val json = serializeBookDataJson(data)
        tempFile.writeText(json, Charsets.UTF_8)

        // Atomic file replacement via rename
        val renamed = tempFile.renameTo(targetFile)
        if (!renamed) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Safe, self-contained JSON Serialization & Parsing (zero external deps)
    // -------------------------------------------------------------------------

    private fun serializeBookDataJson(data: BookData): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"characters\": [\n")

        val chars = data.characters.values.toList()
        chars.forEachIndexed { index, char ->
            sb.append("    {\n")
            sb.append("      \"id\": ").append(escapeJsonString(char.id)).append(",\n")
            sb.append("      \"name\": ").append(escapeJsonString(char.name)).append(",\n")
            sb.append("      \"aliases\": [")
            sb.append(char.aliases.joinToString(", ") { escapeJsonString(it) })
            sb.append("],\n")
            sb.append("      \"voiceId\": ").append(char.voiceId?.let { escapeJsonString(it) } ?: "null").append(",\n")
            sb.append("      \"voiceGender\": ").append(escapeJsonString(char.voiceGender.name)).append(",\n")
            sb.append("      \"readByNarrator\": ").append(char.readByNarrator).append(",\n")
            sb.append("      \"speed\": ").append(char.speed).append(",\n")
            sb.append("      \"pitch\": ").append(char.pitch).append("\n")
            sb.append("    }")
            if (index < chars.size - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("  ],\n")
        sb.append("  \"overrides\": {\n")

        val chapterEntries = data.overrides.entries.toList()
        chapterEntries.forEachIndexed { cIndex, (chapterId, segMap) ->
            sb.append("    ").append(escapeJsonString(chapterId)).append(": {\n")
            val segEntries = segMap.entries.toList()
            segEntries.forEachIndexed { sIndex, (segId, charId) ->
                sb.append("      ").append(escapeJsonString(segId)).append(": ").append(escapeJsonString(charId))
                if (sIndex < segEntries.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("    }")
            if (cIndex < chapterEntries.size - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("  },\n")
        sb.append("  \"ignoredNames\": [")
        sb.append(data.ignoredNames.joinToString(", ") { escapeJsonString(it) })
        sb.append("]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun parseBookDataJson(jsonStr: String): BookData? {
        val parsed = MiniJson.parse(jsonStr) as? Map<*, *> ?: return null

        val charactersMap = linkedMapOf<String, Character>()
        val charListRaw = parsed["characters"] as? List<*>
        if (charListRaw != null) {
            for (rawItem in charListRaw) {
                val item = rawItem as? Map<*, *> ?: continue
                val id = item["id"] as? String ?: continue
                val name = item["name"] as? String ?: id
                val aliases = (item["aliases"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val voiceId = item["voiceId"] as? String
                val genderStr = item["voiceGender"] as? String ?: "ANY"
                val gender = try {
                    VoiceGender.valueOf(genderStr)
                } catch (_: Exception) {
                    VoiceGender.ANY
                }
                val readByNarrator = (item["readByNarrator"] as? Boolean) ?: false
                val speed = ((item["speed"] as? Number)?.toFloat()) ?: Character.DEFAULT_SPEED
                val pitch = ((item["pitch"] as? Number)?.toFloat()) ?: Character.DEFAULT_PITCH

                charactersMap[id] = Character(
                    id = id,
                    name = name,
                    aliases = aliases,
                    voiceId = voiceId,
                    voiceGender = gender,
                    readByNarrator = readByNarrator,
                    speed = speed,
                    pitch = pitch
                )
            }
        }

        val overridesMap = mutableMapOf<String, MutableMap<String, String>>()
        val rawOverrides = parsed["overrides"] as? Map<*, *>
        if (rawOverrides != null) {
            for ((chKey, chVal) in rawOverrides) {
                val chapterId = chKey as? String ?: continue
                val segMap = chVal as? Map<*, *> ?: continue
                val inner = mutableMapOf<String, String>()
                for ((sKey, sVal) in segMap) {
                    val segId = sKey as? String ?: continue
                    val charId = sVal as? String ?: continue
                    inner[segId] = charId
                }
                overridesMap[chapterId] = inner
            }
        }

        val ignoredSet = mutableSetOf<String>()
        val rawIgnored = parsed["ignoredNames"] as? List<*>
        if (rawIgnored != null) {
            for (item in rawIgnored) {
                if (item is String && item.isNotBlank()) {
                    ignoredSet.add(item.trim().lowercase())
                }
            }
        }

        ensureBuiltInsExist(charactersMap)
        return BookData(characters = charactersMap, overrides = overridesMap, ignoredNames = ignoredSet)
    }

    /**
     * Resilient recursive-descent JSON parser with no external dependencies.
     */
    private object MiniJson {
        fun parse(jsonStr: String): Any? {
            val text = jsonStr.trim()
            if (text.isEmpty()) return null
            var pos = 0

            fun skipWs() {
                while (pos < text.length && text[pos].isWhitespace()) pos++
            }

            fun peek(): Char? = if (pos < text.length) text[pos] else null

            fun readString(): String {
                pos++ // skip initial '"'
                val sb = StringBuilder()
                while (pos < text.length) {
                    val c = text[pos++]
                    if (c == '"') return sb.toString()
                    if (c == '\\' && pos < text.length) {
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= text.length) {
                                    val hex = text.substring(pos, pos + 4)
                                    pos += 4
                                    try {
                                        sb.append(hex.toInt(16).toChar())
                                    } catch (_: Exception) {}
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                return sb.toString()
            }

            fun parseValue(): Any? {
                skipWs()
                val c = peek() ?: return null
                return when {
                    c == '{' -> {
                        pos++
                        val map = mutableMapOf<String, Any?>()
                        skipWs()
                        if (peek() == '}') {
                            pos++
                            return map
                        }
                        while (pos < text.length) {
                            skipWs()
                            if (peek() != '"') break
                            val key = readString()
                            skipWs()
                            if (peek() == ':') pos++
                            val v = parseValue()
                            map[key] = v
                            skipWs()
                            if (peek() == ',') {
                                pos++
                            } else if (peek() == '}') {
                                pos++
                                break
                            } else {
                                break
                            }
                        }
                        map
                    }
                    c == '[' -> {
                        pos++
                        val list = mutableListOf<Any?>()
                        skipWs()
                        if (peek() == ']') {
                            pos++
                            return list
                        }
                        while (pos < text.length) {
                            val v = parseValue()
                            list.add(v)
                            skipWs()
                            if (peek() == ',') {
                                pos++
                            } else if (peek() == ']') {
                                pos++
                                break
                            } else {
                                break
                            }
                        }
                        list
                    }
                    c == '"' -> readString()
                    c == 't' && text.startsWith("true", pos) -> { pos += 4; true }
                    c == 'f' && text.startsWith("false", pos) -> { pos += 5; false }
                    c == 'n' && text.startsWith("null", pos) -> { pos += 4; null }
                    c == '-' || c in '0'..'9' -> {
                        val start = pos
                        if (text[pos] == '-') pos++
                        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
                        val num = text.substring(start, pos)
                        num.toDoubleOrNull() ?: 0.0
                    }
                    else -> {
                        pos++
                        null
                    }
                }
            }

            return try {
                parseValue()
            } catch (_: Exception) {
                null
            }
        }
    }
}
