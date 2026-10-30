package com.novelreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.novelreader.tts.core.model.VoiceGender
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
                        debugTracker.onStart(event.utteranceId, activeSpeaker)
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

@Composable
fun ReaderScreen(
    controller: PlaybackController,
    debugTracker: TtsDebugTracker,
    onPlayClicked: () -> Unit
) {
    val readerState by controller.readerState.collectAsState()
    val debugMetrics by debugTracker.metrics.collectAsState()

    val sampleText = remember {
        """
        The dogs' bones were buried near Xian's sword. 'tis a dark night.
        "Run," said Lin.
        Lin said, "Run."
        "Run," he said.
        He said to Xian:
        "We leave at dawn."
        "Prepare the carriage," replied Master Xian calmly.
        The horses stamped their hooves in the cold mist.
        """.trimIndent()
    }

    var segments by remember {
        val rawSegments = NovelSegmenter.segment(sampleText)
        val knownCharacters = listOf(
            Character.Narrator,
            Character.Unnamed,
            Character(
                id = "char_xian",
                name = "Master Xian",
                aliases = listOf("Xian"),
                voiceGender = VoiceGender.MALE
            )
        )
        val attribution = TagAttributor().attribute(rawSegments, knownCharacters)
        controller.updateCharacters(attribution.characters)
        controller.loadSegments(attribution.segments)
        mutableStateOf(attribution.segments)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Bar: Player Title & Readiness
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
                    text = if (readerState.isReady) "TTS Engine: Ready" else "TTS Engine: Initializing...",
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

        Spacer(modifier = Modifier.height(12.dp))

        // Debug Instrumentation Card
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "DEBUG INSTRUMENTATION (TTS Transition Latency)",
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
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Segment & Sentence View
        Text(
            text = "Chapter Segments (${segments.size}) — Sentence ${readerState.currentSentenceIndex + 1} of ${readerState.sentenceCount}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            itemsIndexed(segments) { index, segment ->
                val isHighlighted = readerState.sentenceHighlight?.let { hl ->
                    segment.startOffset <= hl.startOffset && segment.endOffset >= hl.endOffset
                } ?: false

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isHighlighted) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "ID: ${segment.id} [${segment.type}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Speaker: ${segment.effectiveSpeakerId}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = segment.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Playback Controls Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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
}
