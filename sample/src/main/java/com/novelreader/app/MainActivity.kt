package com.novelreader.app

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.novelreader.tts.android.engine.SystemTtsEngine
import com.novelreader.tts.core.engine.SpeechEvent
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.SegmentType
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.model.VoiceInfo
import com.novelreader.tts.core.model.VoiceSettings
import com.novelreader.tts.core.playback.PlaybackController
import com.novelreader.tts.core.segmenter.NovelSegmenter
import com.novelreader.tts.core.segmenter.TagAttributor
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var ttsEngine: SystemTtsEngine
    private lateinit var controller: PlaybackController
    private val debugTracker = TtsDebugTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ttsEngine = SystemTtsEngine(this)
        controller = PlaybackController(ttsEngine, scope = lifecycleScope)

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
                    ReaderScreen(
                        ttsEngine = ttsEngine,
                        controller = controller,
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

private const val DEFAULT_SAMPLE_TEXT = """The dogs' bones were buried near Xian's sword. 'tis a dark night.
"Run," said Lin.
Lin said, "Run."
"Run," he said.
He said to Xian:
"We leave at dawn."
"Prepare the carriage," replied Master Xian calmly.
The horses stamped their hooves in the cold mist."""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    ttsEngine: SystemTtsEngine,
    controller: PlaybackController,
    debugTracker: TtsDebugTracker,
    onPlayClicked: () -> Unit
) {
    val readerState by controller.readerState.collectAsState()
    val debugMetrics by debugTracker.metrics.collectAsState()

    var chapterInputText by remember { mutableStateOf(DEFAULT_SAMPLE_TEXT) }
    var availableVoices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }

    var narratorVoice by remember { mutableStateOf<VoiceInfo?>(null) }
    var unnamedVoice by remember { mutableStateOf<VoiceInfo?>(null) }

    var characters by remember {
        mutableStateOf(
            listOf(
                Character.Narrator,
                Character.Unnamed,
                Character(
                    id = "char_xian",
                    name = "Master Xian",
                    aliases = listOf("Xian"),
                    voiceGender = VoiceGender.MALE
                )
            )
        )
    }

    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var segmentForSpeakerSheet by remember { mutableStateOf<Segment?>(null) }
    var showAddCharacterDialog by remember { mutableStateOf(false) }

    // When TTS engine is ready, load available real voices from device (offline only)
    LaunchedEffect(readerState.isReady) {
        if (readerState.isReady) {
            val deviceVoices = ttsEngine.getAvailableVoices().filter { !it.requiresNetwork }
            availableVoices = deviceVoices
            controller.updateAvailableVoices(deviceVoices)

            if (narratorVoice == null && deviceVoices.isNotEmpty()) {
                narratorVoice = deviceVoices.firstOrNull { it.locale.startsWith("en") } ?: deviceVoices.firstOrNull()
            }
            if (unnamedVoice == null && deviceVoices.size > 1) {
                unnamedVoice = deviceVoices.getOrNull(1) ?: deviceVoices.firstOrNull()
            }
        }
    }

    // Helper to perform segmentation and tag attribution
    fun processAndLoadChapter(textToLoad: String) {
        val rawSegments = NovelSegmenter.segment(textToLoad)
        val attribution = TagAttributor().attribute(rawSegments, characters)

        // Merge newly discovered characters
        val mergedChars = (characters + attribution.characters).distinctBy { it.id }
        characters = mergedChars

        controller.updateCharacters(mergedChars)
        controller.loadSegments(attribution.segments)
        segments = attribution.segments
    }

    // Initial load
    LaunchedEffect(Unit) {
        processAndLoadChapter(chapterInputText)
    }

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
            Column {
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

        // 6. Visible Error Banners
        if (readerState.isReady && availableVoices.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
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
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Missing voice",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
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

        // 7. Debug Panel (Latencies + Last Voice Actually Used)
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "DEBUG INSTRUMENTATION & VOICE ROUTING",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "First Start: ${debugMetrics.firstStartLatencyMs?.let { "${it}ms (${debugMetrics.firstStartLabel})" } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Median Gap: ${debugMetrics.medianGapMs?.let { "${it}ms" } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Max Gap: ${debugMetrics.maxGapMs?.let { "${it}ms" } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Voice actually used: ${debugMetrics.lastVoiceName ?: readerState.activeVoiceName ?: "None yet"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. Chapter Paste Field + Load / Sample Buttons
        OutlinedTextField(
            value = chapterInputText,
            onValueChange = { chapterInputText = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            label = { Text("Paste Chapter Text") },
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { processAndLoadChapter(chapterInputText) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Load & Segment")
            }

            OutlinedButton(
                onClick = {
                    chapterInputText = DEFAULT_SAMPLE_TEXT
                    processAndLoadChapter(DEFAULT_SAMPLE_TEXT)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Use Sample")
            }

            FilledTonalButton(
                onClick = { showAddCharacterDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Character")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Voice Pickers for Narrator and Unnamed with Preview ▶ Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VoicePickerItem(
                label = "Narrator Voice",
                modifier = Modifier.weight(1f),
                availableVoices = availableVoices,
                selectedVoice = narratorVoice,
                onVoiceSelected = { voice ->
                    narratorVoice = voice
                    characters = characters.map {
                        if (it.id == Character.NARRATOR_ID) it.copy(voiceId = voice.id) else it
                    }
                    controller.updateCharacters(characters)
                },
                onPreview = { voice ->
                    ttsEngine.speak(
                        sentence = "This is a preview of the narrator voice.",
                        voice = voice,
                        settings = VoiceSettings.Default
                    )
                }
            )

            VoicePickerItem(
                label = "Unnamed Dialogue",
                modifier = Modifier.weight(1f),
                availableVoices = availableVoices,
                selectedVoice = unnamedVoice,
                onVoiceSelected = { voice ->
                    unnamedVoice = voice
                    characters = characters.map {
                        if (it.id == Character.UNNAMED_ID) it.copy(voiceId = voice.id) else it
                    }
                    controller.updateCharacters(characters)
                },
                onPreview = { voice ->
                    ttsEngine.speak(
                        sentence = "This is a preview of the unnamed speaker voice.",
                        voice = voice,
                        settings = VoiceSettings.Default
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 5. Segments List with Effective Speaker Names
        Text(
            text = "Chapter Segments (${segments.size}) — Tap a dialogue quote to reassign speaker:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            itemsIndexed(segments) { index, segment ->
                val isHighlighted = readerState.sentenceHighlight?.let { hl ->
                    segment.startOffset <= hl.startOffset && segment.endOffset >= hl.endOffset
                } ?: false

                // 5. Find effective speaker display name
                val speakerChar = characters.firstOrNull { it.id == segment.effectiveSpeakerId }
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
                                Modifier.clickable {
                                    // 4. Tap dialogue to assign speaker
                                    segmentForSpeakerSheet = segment
                                }
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

                            // 5. Effective speaker name clearly displayed next to segment
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

    // 4. Modal Bottom Sheet for Speaker Assignment
    if (segmentForSpeakerSheet != null) {
        val targetSegment = segmentForSpeakerSheet!!
        val sheetState = rememberModalBottomSheetState()

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

                // List of selectable characters: Unnamed, Narrator, and custom characters
                LazyColumn {
                    items(characters) { char ->
                        val isSelected = targetSegment.effectiveSpeakerId == char.id

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    // 4. Assign via speakerOverride and play immediately in the new voice
                                    controller.reassignSpeaker(targetSegment.id, char.id, isOverride = true)
                                    segments = segments.map {
                                        if (it.id == targetSegment.id) it.copy(speakerOverride = char.id) else it
                                    }
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
                                        text = "Voice: ${assignedVoice?.name ?: char.voiceId ?: "Auto-selected"}",
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

    // 3. Add Character Dialog
    if (showAddCharacterDialog) {
        var newCharName by remember { mutableStateOf("") }
        var selectedVoiceForChar by remember { mutableStateOf<VoiceInfo?>(availableVoices.firstOrNull()) }

        AlertDialog(
            onDismissRequest = { showAddCharacterDialog = false },
            title = { Text("Add Character") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newCharName,
                        onValueChange = { newCharName = it },
                        label = { Text("Character Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Assign Voice:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    VoicePickerItem(
                        label = "Voice",
                        modifier = Modifier.fillMaxWidth(),
                        availableVoices = availableVoices,
                        selectedVoice = selectedVoiceForChar,
                        onVoiceSelected = { selectedVoiceForChar = it },
                        onPreview = { voice ->
                            val previewText = if (newCharName.isNotBlank()) {
                                "Hello, my name is $newCharName."
                            } else {
                                "Hello, this is a voice preview."
                            }
                            ttsEngine.speak(previewText, voice = voice, settings = VoiceSettings.Default)
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCharName.isNotBlank()) {
                            val newChar = Character(
                                id = "char_${newCharName.lowercase().replace(Regex("[^a-z0-9]"), "_")}_${System.currentTimeMillis()}",
                                name = newCharName.trim(),
                                voiceId = selectedVoiceForChar?.id,
                                voiceGender = selectedVoiceForChar?.gender ?: VoiceGender.ANY
                            )
                            characters = characters + newChar
                            controller.updateCharacters(characters)
                            showAddCharacterDialog = false
                        }
                    },
                    enabled = newCharName.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCharacterDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun VoicePickerItem(
    label: String,
    modifier: Modifier = Modifier,
    availableVoices: List<VoiceInfo>,
    selectedVoice: VoiceInfo?,
    onVoiceSelected: (VoiceInfo) -> Unit,
    onPreview: (VoiceInfo) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { expanded = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedVoice?.let { "${it.name} (${it.gender})" } ?: "Select Voice",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select")
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (availableVoices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No voices available") },
                                onClick = { expanded = false }
                            )
                        } else {
                            availableVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = voice.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${voice.locale} • Gender: ${voice.gender}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    },
                                    onClick = {
                                        onVoiceSelected(voice)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Preview ▶ Button
                IconButton(
                    onClick = {
                        selectedVoice?.let { onPreview(it) }
                    },
                    enabled = selectedVoice != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Preview Voice",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
