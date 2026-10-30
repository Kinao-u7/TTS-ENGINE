package com.novelreader.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.novelreader.tts.android.engine.SystemTtsEngine
import com.novelreader.tts.android.store.JsonFileCastStore
import com.novelreader.tts.core.engine.SpeechEvent
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.CharacterResolver
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.model.VoiceInfo
import com.novelreader.tts.core.model.VoiceSelector
import com.novelreader.tts.core.model.VoiceSettings
import com.novelreader.tts.core.playback.PlaybackController
import com.novelreader.tts.core.segmenter.NovelSegmenter
import com.novelreader.tts.core.segmenter.TagAttributor
import com.novelreader.tts.core.store.CastStore
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var ttsEngine: SystemTtsEngine
    private lateinit var controller: PlaybackController
    private lateinit var castStore: CastStore
    private val debugTracker = TtsDebugTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ttsEngine = SystemTtsEngine(this)
        controller = PlaybackController(ttsEngine, scope = lifecycleScope)
        castStore = JsonFileCastStore(this)

        // Wire debug instrumentation to TTS engine speech events
        lifecycleScope.launch {
            ttsEngine.events.collect { event ->
                when (event) {
                    is SpeechEvent.Started -> {
                        val activeSpeaker = controller.readerState.value.currentSpeaker?.id ?: "unknown"
                        val activeVoice = controller.readerState.value.activeVoiceName
                            ?: controller.readerState.value.currentSpeaker?.voiceName
                        debugTracker.onStart(event.utteranceId, activeSpeaker, activeVoice)
                    }
                    is SpeechEvent.Done -> {
                        val activeSpeaker = controller.readerState.value.currentSpeaker?.id ?: "unknown"
                        debugTracker.onDone(event.utteranceId, activeSpeaker)
                    }
                    else -> {}
                }
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppScaffold(
                        ttsEngine = ttsEngine,
                        controller = controller,
                        castStore = castStore,
                        debugTracker = debugTracker,
                        onPlayClicked = {
                            val isWarm = ttsEngine.isReady.value
                            debugTracker.onPlay(isWarm)
                            controller.play()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
    }
}

private const val STABLE_BOOK_ID = "test_book"

fun deriveChapterId(text: String): String {
    val hash = NovelSegmenter.computeShortHash(text)
    return "ch_$hash"
}

fun formatReadableLocale(localeTag: String): String {
    return try {
        val tag = localeTag.replace('_', '-')
        val locale = Locale.forLanguageTag(tag)
        val lang = locale.getDisplayLanguage(Locale.ENGLISH)
        val country = locale.getDisplayCountry(Locale.ENGLISH)
        if (country.isNotBlank()) "$lang ($country)" else if (lang.isNotBlank()) lang else localeTag
    } catch (_: Exception) {
        localeTag
    }
}

@Composable
fun MainAppScaffold(
    ttsEngine: SystemTtsEngine,
    controller: PlaybackController,
    castStore: CastStore,
    debugTracker: TtsDebugTracker,
    onPlayClicked: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var autoDetectSpeakers by remember { mutableStateOf(prefs.getBoolean("auto_detect_speakers", true)) }

    val charactersList by castStore.characters(STABLE_BOOK_ID).collectAsState(initial = emptyList())
    var availableVoices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }

    // Remember the last pasted text across restarts (empty string on fresh launch)
    var chapterInputText by remember {
        mutableStateOf(prefs.getString("last_pasted_text", "") ?: "")
    }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }

    val readerState by controller.readerState.collectAsState()

    // Sync characters with PlaybackController
    LaunchedEffect(charactersList) {
        if (charactersList.isNotEmpty()) {
            controller.updateCharacters(charactersList)
        }
    }

    // Load available voices once TTS engine is ready
    LaunchedEffect(readerState.isReady) {
        if (readerState.isReady) {
            val deviceVoices = ttsEngine.getAvailableVoices().filter { !it.requiresNetwork }
            availableVoices = deviceVoices
            controller.updateAvailableVoices(deviceVoices)
        }
    }

    // Function to load and segment chapter, stopping playback, clearing old segments,
    // running TagAttributor (setting speakerId only), auto-creating characters with distinct voices,
    // and applying stored overrides based on the text hash chapterId.
    fun processAndLoadChapter(textToLoad: String) {
        scope.launch {
            // Stop playback and clear previous segments completely
            controller.stop()
            segments = emptyList()
            controller.loadSegments(emptyList())

            if (textToLoad.isBlank()) {
                prefs.edit().putString("last_pasted_text", "").apply()
                return@launch
            }

            // Persist the pasted text
            prefs.edit().putString("last_pasted_text", textToLoad).apply()

            // Derive chapterId from hash of the loaded text so overrides never collide
            val chapterId = deriveChapterId(textToLoad)

            val rawSegments = NovelSegmenter.segment(textToLoad)
            val currentChars = if (charactersList.isEmpty()) {
                listOf(Character.Narrator, Character.Unnamed)
            } else {
                charactersList
            }

            val attributedSegments = if (autoDetectSpeakers) {
                val ignoredNames = castStore.ignoredNames(STABLE_BOOK_ID)
                val attribution = TagAttributor().attribute(rawSegments, currentChars, ignoredNames)

                // For any newly created character from TagAttributor, pick a distinct voice once and save it
                val usedVoiceIds = currentChars.mapNotNull { it.voiceId }.toMutableSet()
                val narratorVoiceId = currentChars.firstOrNull { it.id == Character.NARRATOR_ID }?.voiceId

                for (newChar in attribution.characters) {
                    if (currentChars.none { it.id == newChar.id }) {
                        val voiceResult = VoiceSelector.pick(
                            character = newChar,
                            availableVoices = availableVoices,
                            usedVoices = usedVoiceIds,
                            bookLocale = "en-US",
                            narratorVoiceId = narratorVoiceId
                        )
                        val charWithVoice = newChar.copy(voiceId = voiceResult.voice?.id)
                        voiceResult.voice?.id?.let { usedVoiceIds.add(it) }
                        castStore.saveCharacter(STABLE_BOOK_ID, charWithVoice)
                    }
                }
                attribution.segments
            } else {
                rawSegments
            }

            // Load stored overrides for this book & chapterId
            val overridesMap = castStore.overrides(STABLE_BOOK_ID, chapterId)

            // Apply overrides as speakerOverride (ignoring non-existent segment IDs silently)
            val finalSegments = attributedSegments.map { seg ->
                val overrideId = overridesMap[seg.id]
                if (overrideId != null) {
                    seg.copy(speakerOverride = overrideId)
                } else {
                    seg
                }
            }

            segments = finalSegments
            controller.loadSegments(finalSegments)
        }
    }

    // Initial load: if user had previously pasted text, restore it
    LaunchedEffect(Unit) {
        if (chapterInputText.isNotBlank()) {
            processAndLoadChapter(chapterInputText)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab Navigation: Reader and Cast
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Reader") },
                icon = { Icon(Icons.Default.MenuBook, contentDescription = "Reader") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Cast (${charactersList.size})") },
                icon = { Icon(Icons.Default.Group, contentDescription = "Cast") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                ReaderTabContent(
                    controller = controller,
                    castStore = castStore,
                    debugTracker = debugTracker,
                    charactersList = charactersList,
                    availableVoices = availableVoices,
                    chapterInputText = chapterInputText,
                    onChapterInputTextChange = { chapterInputText = it },
                    segments = segments,
                    onSegmentsChange = { segments = it },
                    onLoadChapter = { processAndLoadChapter(it) },
                    onPlayClicked = onPlayClicked,
                    onOpenCastScreen = { selectedTab = 1 }
                )
            } else {
                CastTabContent(
                    ttsEngine = ttsEngine,
                    controller = controller,
                    castStore = castStore,
                    charactersList = charactersList,
                    availableVoices = availableVoices,
                    segments = segments,
                    autoDetectSpeakers = autoDetectSpeakers,
                    onAutoDetectSpeakersChange = { enabled ->
                        autoDetectSpeakers = enabled
                        prefs.edit().putBoolean("auto_detect_speakers", enabled).apply()
                        if (chapterInputText.isNotBlank()) {
                            processAndLoadChapter(chapterInputText)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTabContent(
    controller: PlaybackController,
    castStore: CastStore,
    debugTracker: TtsDebugTracker,
    charactersList: List<Character>,
    availableVoices: List<VoiceInfo>,
    chapterInputText: String,
    onChapterInputTextChange: (String) -> Unit,
    segments: List<Segment>,
    onSegmentsChange: (List<Segment>) -> Unit,
    onLoadChapter: (String) -> Unit,
    onPlayClicked: () -> Unit,
    onOpenCastScreen: () -> Unit
) {
    val readerState by controller.readerState.collectAsState()
    val debugMetrics by debugTracker.metrics.collectAsState()
    val scope = rememberCoroutineScope()

    var segmentForSpeakerSheet by remember { mutableStateOf<Segment?>(null) }

    val dialogueSegments = segments.filter { it.type == SegmentType.DIALOGUE }
    val autoAssignedCount = dialogueSegments.count { it.speakerId != null }
    val unnamedCount = dialogueSegments.count { it.speakerId == null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Novel Reader Multi-Voice TTS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (readerState.isReady) "TTS Engine: Ready (${availableVoices.size} offline voices)" else "TTS Engine: Initializing...",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (readerState.isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }

            // Current Speaker Chip
            readerState.currentSpeaker?.let { speaker ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Speaker: ${speaker.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Error Banner
        if (readerState.isReady && availableVoices.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No offline TTS voices found on this device! Check system Text-to-Speech settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        readerState.missingVoiceWarning?.let { warning ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Missing voice", tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Debug Panel & Dialogue Attribution Stats
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "First: ${debugMetrics.firstStartLatencyMs?.let { "${it}ms" } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Med Gap: ${debugMetrics.medianGapMs?.let { "${it}ms" } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Voice: ${debugMetrics.lastVoiceName ?: readerState.activeVoiceName ?: "None yet"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Debug line showing how many dialogue lines were auto-assigned vs Unnamed
                Text(
                    text = "Dialogue: $autoAssignedCount auto-assigned, $unnamedCount Unnamed (${dialogueSegments.size} total)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chapter Input Field
        OutlinedTextField(
            value = chapterInputText,
            onValueChange = onChapterInputTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            label = { Text("Paste Chapter Text") },
            placeholder = { Text("Paste novel chapter text here...") },
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Action Buttons Row (Only "Load" and "Cast", no sample button)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onLoadChapter(chapterInputText) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Load")
            }

            FilledTonalButton(onClick = onOpenCastScreen) {
                Icon(Icons.Default.Group, contentDescription = "Cast", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cast")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Segments List
        Text(
            text = "Chapter Segments (${segments.size}) — Tap dialogue to assign speaker:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (segments.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No segments loaded. Paste text above and tap Load.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                itemsIndexed(segments) { _, segment ->
                    val isHighlighted = readerState.sentenceHighlight?.let { hl ->
                        segment.startOffset <= hl.startOffset && segment.endOffset >= hl.endOffset
                    } ?: false

                    val speakerChar = charactersList.firstOrNull { it.id == segment.effectiveSpeakerId }
                    val effectiveDisplayName = speakerChar?.name ?: when (segment.effectiveSpeakerId) {
                        Character.NARRATOR_ID -> "Narrator"
                        Character.UNNAMED_ID -> "Unnamed"
                        else -> segment.effectiveSpeakerId
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (segment.type == SegmentType.DIALOGUE) {
                                    Modifier.clickable { segmentForSpeakerSheet = segment }
                                } else {
                                    Modifier
                                }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isHighlighted) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            } else if (segment.type == SegmentType.DIALOGUE) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${segment.type} [${segment.id}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Reader: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = effectiveDisplayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (segment.speakerOverride != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                    )
                                    if (segment.speakerOverride != null) {
                                        Text(
                                            text = " (override)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = segment.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Playback Controls Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.previous() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
            }

            Spacer(modifier = Modifier.width(16.dp))

            FloatingActionButton(
                onClick = {
                    if (readerState.isPlaying) {
                        controller.pause()
                    } else {
                        onPlayClicked()
                    }
                }
            ) {
                Icon(
                    imageVector = if (readerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (readerState.isPlaying) "Pause" else "Play"
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            IconButton(onClick = { controller.next() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }

    // Modal Bottom Sheet for Speaker Assignment
    if (segmentForSpeakerSheet != null) {
        val targetSegment = segmentForSpeakerSheet!!
        val sheetState = rememberModalBottomSheetState()
        val chapterId = deriveChapterId(chapterInputText)

        ModalBottomSheet(
            onDismissRequest = { segmentForSpeakerSheet = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Assign Speaker to Dialogue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = targetSegment.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn {
                    items(charactersList) { char ->
                        val isSelected = targetSegment.effectiveSpeakerId == char.id

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    scope.launch {
                                        castStore.setOverride(STABLE_BOOK_ID, chapterId, targetSegment.id, char.id)
                                    }
                                    controller.reassignSpeaker(targetSegment.id, char.id, isOverride = true)
                                    val updated = segments.map {
                                        if (it.id == targetSegment.id) it.copy(speakerOverride = char.id) else it
                                    }
                                    onSegmentsChange(updated)
                                    controller.playSegment(targetSegment.id)
                                    segmentForSpeakerSheet = null
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = char.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val assignedVoice = availableVoices.firstOrNull { it.id == char.voiceId }
                                    Text(
                                        text = if (char.readByNarrator) {
                                            "Read by Narrator"
                                        } else {
                                            "Voice: ${assignedVoice?.let { "${it.name} (${formatReadableLocale(it.locale)})" } ?: "Auto-assigned"}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastTabContent(
    ttsEngine: SystemTtsEngine,
    controller: PlaybackController,
    castStore: CastStore,
    charactersList: List<Character>,
    availableVoices: List<VoiceInfo>,
    segments: List<Segment>,
    autoDetectSpeakers: Boolean,
    onAutoDetectSpeakersChange: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var characterToEdit by remember { mutableStateOf<Character?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Character Cast",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${charactersList.size} characters configured in this book",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Character")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Auto-detect speakers toggle card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-detect speakers",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Infer speaker from dialogue tags (e.g. said Xian)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Switch(
                    checked = autoDetectSpeakers,
                    onCheckedChange = onAutoDetectSpeakersChange
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Characters List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(charactersList) { char ->
                val lineCount = segments.count { it.effectiveSpeakerId == char.id }
                val assignedVoice = availableVoices.firstOrNull { it.id == char.voiceId }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { characterToEdit = char },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar Initial
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char.name.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Character details
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = char.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (char.isBuiltIn) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "(Built-in)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            val voiceDesc = if (char.readByNarrator) {
                                "Read by Narrator"
                            } else if (assignedVoice != null) {
                                "${assignedVoice.name} (${formatReadableLocale(assignedVoice.locale)})"
                            } else {
                                "Auto-assigned (${char.voiceGender.name})"
                            }

                            Text(
                                text = "$voiceDesc • $lineCount ${if (lineCount == 1) "line" else "lines"} • ${char.speed}x speed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        // Preview Play ▶ Button
                        IconButton(
                            onClick = {
                                val usedVoiceIds = charactersList.mapNotNull { it.voiceId }.toSet()
                                val narratorVoiceId = charactersList.firstOrNull { it.id == Character.NARRATOR_ID }?.voiceId
                                val voiceResult = VoiceSelector.pick(
                                    character = char,
                                    availableVoices = availableVoices,
                                    usedVoices = usedVoiceIds,
                                    bookLocale = "en-US",
                                    narratorVoiceId = narratorVoiceId
                                )
                                val settings = VoiceSettings.getSettings(char)
                                val previewText = "Hello, I am ${char.name}."
                                ttsEngine.speak(previewText, voice = voiceResult.voice, settings = settings, utteranceId = "preview_${char.id}")
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Preview voice", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    // Edit Character Sheet
    if (characterToEdit != null) {
        val char = characterToEdit!!
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        var editedName by remember(char.id) { mutableStateOf(char.name) }
        var selectedVoiceId by remember(char.id) { mutableStateOf(char.voiceId) }
        var selectedGender by remember(char.id) { mutableStateOf(char.voiceGender) }
        var editedSpeed by remember(char.id) { mutableFloatStateOf(char.speed) }
        var editedPitch by remember(char.id) { mutableFloatStateOf(char.pitch) }
        var readByNarrator by remember(char.id) { mutableStateOf(char.readByNarrator) }
        var showVoiceDropdown by remember(char.id) { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { characterToEdit = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = if (char.isBuiltIn) "Edit ${char.name} Settings" else "Edit Character",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Name field
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { if (!char.isBuiltIn) editedName = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !char.isBuiltIn,
                    supportingText = {
                        if (char.isBuiltIn) Text("Built-in character names cannot be changed")
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Voice Picker
                Text(
                    text = "Assigned Voice",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))

                val currentSelectedVoice = availableVoices.firstOrNull { it.id == selectedVoiceId }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showVoiceDropdown = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentSelectedVoice?.let { "${it.name} (${formatReadableLocale(it.locale)})" } ?: "Auto-assigned (matches gender)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select voice")
                        }
                    }

                    DropdownMenu(
                        expanded = showVoiceDropdown,
                        onDismissRequest = { showVoiceDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Auto-assign (Default)") },
                            onClick = {
                                selectedVoiceId = null
                                showVoiceDropdown = false
                            }
                        )
                        availableVoices.forEach { voice ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(text = voice.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = formatReadableLocale(voice.locale),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                },
                                onClick = {
                                    selectedVoiceId = voice.id
                                    showVoiceDropdown = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Voice Gender
                Text(
                    text = "Voice Gender Preference",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(VoiceGender.ANY, VoiceGender.MALE, VoiceGender.FEMALE).forEach { gender ->
                        FilterChip(
                            selected = selectedGender == gender,
                            onClick = { selectedGender = gender },
                            label = { Text(gender.name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Speed Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Speed", style = MaterialTheme.typography.labelMedium)
                    Text(text = String.format(Locale.US, "%.1fx", editedSpeed), style = MaterialTheme.typography.bodySmall)
                }
                Slider(
                    value = editedSpeed,
                    onValueChange = { editedSpeed = it },
                    valueRange = 0.5f..2.0f,
                    steps = 14
                )

                // Pitch Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Pitch", style = MaterialTheme.typography.labelMedium)
                    Text(text = String.format(Locale.US, "%.1fx", editedPitch), style = MaterialTheme.typography.bodySmall)
                }
                Slider(
                    value = editedPitch,
                    onValueChange = { editedPitch = it },
                    valueRange = 0.5f..2.0f,
                    steps = 14
                )

                // Read by Narrator toggle
                if (char.id != Character.NARRATOR_ID) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Read by narrator", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(text = "Lines spoken by this character will use the narrator's voice", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        Switch(
                            checked = readByNarrator,
                            onCheckedChange = { readByNarrator = it }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Actions: Delete (if not built-in) and Save
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (char.isDeletable) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    castStore.deleteCharacter(STABLE_BOOK_ID, char.id)
                                    if (chapterInputText.isNotBlank()) {
                                        processAndLoadChapter(chapterInputText)
                                    }
                                }
                                characterToEdit = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }

                    Button(
                        onClick = {
                            val trimmedNewName = editedName.trim()
                            val updatedAliases = if (!trimmedNewName.equals(char.name, ignoreCase = true)) {
                                (char.aliases + char.name).distinctBy { it.lowercase() }
                            } else {
                                char.aliases
                            }
                            val updated = char.copy(
                                name = trimmedNewName,
                                aliases = updatedAliases,
                                voiceId = selectedVoiceId,
                                voiceGender = selectedGender,
                                speed = editedSpeed,
                                pitch = editedPitch,
                                readByNarrator = readByNarrator
                            )
                            scope.launch {
                                castStore.saveCharacter(STABLE_BOOK_ID, updated)
                                if (chapterInputText.isNotBlank()) {
                                    processAndLoadChapter(chapterInputText)
                                }
                            }
                            controller.updateCharacters(charactersList.map { if (it.id == updated.id) updated else it })
                            characterToEdit = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Changes")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Add Character Dialog
    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newVoiceId by remember { mutableStateOf<String?>(null) }
        var newGender by remember { mutableStateOf(VoiceGender.ANY) }
        var newSpeed by remember { mutableFloatStateOf(1.0f) }
        var newPitch by remember { mutableFloatStateOf(1.0f) }
        var newReadByNarrator by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Character") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Character Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = "Gender Preference:", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(VoiceGender.ANY, VoiceGender.MALE, VoiceGender.FEMALE).forEach { g ->
                            FilterChip(
                                selected = newGender == g,
                                onClick = { newGender = g },
                                label = { Text(g.name) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            val id = "char_${newName.lowercase().replace(Regex("[^a-z0-9]"), "_")}_${System.currentTimeMillis()}"
                            val newChar = Character(
                                id = id,
                                name = newName.trim(),
                                voiceId = newVoiceId,
                                voiceGender = newGender,
                                speed = newSpeed,
                                pitch = newPitch,
                                readByNarrator = newReadByNarrator
                            )
                            scope.launch {
                                castStore.saveCharacter(STABLE_BOOK_ID, newChar)
                                if (chapterInputText.isNotBlank()) {
                                    processAndLoadChapter(chapterInputText)
                                }
                            }
                            controller.updateCharacters(charactersList + newChar)
                            showAddDialog = false
                        }
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

