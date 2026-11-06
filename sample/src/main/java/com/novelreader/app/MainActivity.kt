@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novelreader.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.core.content.ContextCompat
import com.novelreader.tts.android.MultiVoiceReader
import com.novelreader.tts.android.service.AudioFocusMode
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.segmenter.NovelSegmenter
import com.novelreader.tts.ui.AddCharacterDialog
import com.novelreader.tts.ui.AssignSpeakerSheet
import com.novelreader.tts.ui.AudioSettingsSheet
import com.novelreader.tts.ui.CastScreen
import com.novelreader.tts.ui.CharacterEditSheet
import com.novelreader.tts.ui.SpeakerChip
import kotlinx.coroutines.launch
import java.util.Locale

private const val STABLE_BOOK_ID = "test_book"

fun deriveChapterId(text: String): String {
    val hash = NovelSegmenter.computeShortHash(text)
    return "ch_$hash"
}

@Composable
fun NovelReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

/**
 * Main application activity acting as a thin shell connecting [MultiVoiceReader] and `:tts-ui`.
 */
class MainActivity : ComponentActivity() {

    private lateinit var reader: MultiVoiceReader
    private val debugTracker = TtsDebugTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = MultiVoiceReader(this)

        setContent {
            NovelReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppScaffold(
                        reader = reader,
                        debugTracker = debugTracker
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reader.release()
    }
}

@Composable
fun MainAppScaffold(
    reader: MultiVoiceReader,
    debugTracker: TtsDebugTracker
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var autoDetectSpeakers by remember { mutableStateOf(prefs.getBoolean("auto_detect_speakers", true)) }

    val readerState by reader.readerState.collectAsState()
    val charactersList by reader.characters.collectAsState(initial = emptyList())
    val segments by reader.segments.collectAsState()
    val availableVoices by reader.availableVoices.collectAsState()
    val resumedFromSentence by reader.resumedFromSentence.collectAsState()

    // Notification permission check on Android 13+
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var segmentForSpeakerSheet by remember { mutableStateOf<Segment?>(null) }
    var characterToEdit by remember { mutableStateOf<Character?>(null) }
    var showAddCharacterDialog by remember { mutableStateOf(false) }

    var audioFocusMode by remember {
        val saved = prefs.getString("audio_focus_mode", AudioFocusMode.PAUSE_OTHERS.name)
            ?: AudioFocusMode.PAUSE_OTHERS.name
        mutableStateOf(try { AudioFocusMode.valueOf(saved) } catch (_: Exception) { AudioFocusMode.PAUSE_OTHERS })
    }

    var keepReadingWhenOtherAudioStarts by remember {
        mutableStateOf(prefs.getBoolean("keep_reading_when_other_audio_starts", false))
    }

    LaunchedEffect(audioFocusMode, keepReadingWhenOtherAudioStarts, reader.isBound) {
        reader.setAudioFocusSettings(audioFocusMode, keepReadingWhenOtherAudioStarts)
    }

    // Persisted chapter input text
    var chapterInputText by remember {
        mutableStateOf(prefs.getString("last_pasted_text", "") ?: "")
    }

    val bookLocale = remember {
        prefs.getString("book_locale", Locale.getDefault().toLanguageTag()) ?: Locale.getDefault().toLanguageTag()
    }

    LaunchedEffect(bookLocale) {
        reader.bookLocale = bookLocale
    }

    fun loadChapterText(text: String) {
        scope.launch {
            prefs.edit().putString("last_pasted_text", text).apply()
            reader.autoDetectSpeakers = autoDetectSpeakers
            val chapterId = deriveChapterId(text)
            reader.loadChapter(STABLE_BOOK_ID, chapterId, text)
        }
    }

    // Initial restore once engine becomes ready
    LaunchedEffect(readerState.isReady) {
        if (readerState.isReady && chapterInputText.isNotBlank() && segments.isEmpty()) {
            loadChapterText(chapterInputText)
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
                // Reader Screen
                ReaderContent(
                    reader = reader,
                    debugTracker = debugTracker,
                    chapterInputText = chapterInputText,
                    onChapterInputTextChange = { chapterInputText = it },
                    onLoadChapter = { loadChapterText(it) },
                    onOpenCastScreen = { selectedTab = 1 },
                    onOpenSettings = { showSettingsSheet = true },
                    onSelectSegmentForSpeaker = { segmentForSpeakerSheet = it },
                    hasNotificationPermission = hasNotificationPermission
                )
            } else {
                // Cast Screen from :tts-ui
                CastScreen(
                    characters = charactersList,
                    availableVoices = availableVoices,
                    segments = segments,
                    autoDetectSpeakers = autoDetectSpeakers,
                    onAutoDetectSpeakersChange = { enabled ->
                        autoDetectSpeakers = enabled
                        prefs.edit().putBoolean("auto_detect_speakers", enabled).apply()
                        reader.autoDetectSpeakers = enabled
                        if (chapterInputText.isNotBlank()) {
                            loadChapterText(chapterInputText)
                        }
                    },
                    onCharacterClick = { characterToEdit = it },
                    onAddCharacterClick = { showAddCharacterDialog = true },
                    onRemoveAutoCreatedClick = {
                        scope.launch {
                            reader.removeAutoCreatedCharacters()
                            if (chapterInputText.isNotBlank()) {
                                loadChapterText(chapterInputText)
                            }
                        }
                    },
                    onPreviewVoice = { char -> reader.previewVoice(char) }
                )
            }
        }
    }

    // Assign Speaker Bottom Sheet from :tts-ui
    segmentForSpeakerSheet?.let { segment ->
        AssignSpeakerSheet(
            segmentText = segment.text,
            characters = charactersList,
            availableVoices = availableVoices,
            selectedSpeakerId = segment.effectiveSpeakerId,
            onSpeakerSelected = { char ->
                scope.launch {
                    reader.assignSpeaker(segment.id, char.id)
                }
                segmentForSpeakerSheet = null
            },
            onDismissRequest = { segmentForSpeakerSheet = null }
        )
    }

    // Edit Character Bottom Sheet from :tts-ui
    characterToEdit?.let { char ->
        CharacterEditSheet(
            character = char,
            availableVoices = availableVoices,
            onSave = { updated ->
                scope.launch {
                    reader.saveCharacter(updated)
                    if (chapterInputText.isNotBlank()) {
                        loadChapterText(chapterInputText)
                    }
                }
                characterToEdit = null
            },
            onDelete = if (char.isDeletable) {
                {
                    scope.launch {
                        reader.deleteCharacter(char.id)
                        if (chapterInputText.isNotBlank()) {
                            loadChapterText(chapterInputText)
                        }
                    }
                    characterToEdit = null
                }
            } else null,
            onDismissRequest = { characterToEdit = null },
            onPreviewVoice = { c -> reader.previewVoice(c) }
        )
    }

    // Add Character Dialog from :tts-ui
    if (showAddCharacterDialog) {
        AddCharacterDialog(
            onAdd = { newChar ->
                scope.launch {
                    reader.saveCharacter(newChar)
                    if (chapterInputText.isNotBlank()) {
                        loadChapterText(chapterInputText)
                    }
                }
                showAddCharacterDialog = false
            },
            onDismissRequest = { showAddCharacterDialog = false }
        )
    }

    // Audio Focus Settings Bottom Sheet from :tts-ui
    if (showSettingsSheet) {
        AudioSettingsSheet(
            audioFocusMode = audioFocusMode,
            keepReadingWhenOtherAudioStarts = keepReadingWhenOtherAudioStarts,
            onModeSelected = { mode ->
                audioFocusMode = mode
                prefs.edit().putString("audio_focus_mode", mode.name).apply()
                reader.setAudioFocusSettings(mode, keepReadingWhenOtherAudioStarts)
            },
            onKeepReadingChanged = { keepReading ->
                keepReadingWhenOtherAudioStarts = keepReading
                prefs.edit().putBoolean("keep_reading_when_other_audio_starts", keepReading).apply()
                reader.setAudioFocusSettings(audioFocusMode, keepReading)
            },
            onDismissRequest = { showSettingsSheet = false }
        )
    }
}

/**
 * Reader tab UI shell displaying chapter text, highlights, attribution stats, and playback controls.
 */
@Composable
fun ReaderContent(
    reader: MultiVoiceReader,
    debugTracker: TtsDebugTracker,
    chapterInputText: String,
    onChapterInputTextChange: (String) -> Unit,
    onLoadChapter: (String) -> Unit,
    onOpenCastScreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectSegmentForSpeaker: (Segment) -> Unit,
    hasNotificationPermission: Boolean
) {
    val readerState by reader.readerState.collectAsState()
    val segments by reader.segments.collectAsState()
    val availableVoices by reader.availableVoices.collectAsState()
    val charactersList by reader.characters.collectAsState(initial = emptyList())
    val resumedFromSentence by reader.resumedFromSentence.collectAsState()
    val debugMetrics by debugTracker.metrics.collectAsState()

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                // SpeakerChip from :tts-ui
                SpeakerChip(speaker = readerState.currentSpeaker)

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Warning Banners
        if (readerState.isReady && availableVoices.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
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

        // Attribution & Latency Debug Stats
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

        // Action Buttons Row
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

            FilledTonalButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Settings")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Notification hint
        if (!hasNotificationPermission) {
            Text(
                text = "Notification controls unavailable (notification permission not granted)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Reading position resumption badge
        if (resumedFromSentence != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Resumed at sentence ${resumedFromSentence!! + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                TextButton(
                    onClick = { reader.jumpToSentence(0) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Start Over", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

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
                                    Modifier.clickable { onSelectSegmentForSpeaker(segment) }
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
            IconButton(onClick = { reader.previous() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
            }

            Spacer(modifier = Modifier.width(16.dp))

            FloatingActionButton(
                onClick = {
                    val isWarm = reader.readerState.value.isReady
                    debugTracker.onPlay(isWarm)
                    reader.togglePlayPause()
                }
            ) {
                Icon(
                    imageVector = if (readerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (readerState.isPlaying) "Pause" else "Play"
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            IconButton(onClick = { reader.next() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}
