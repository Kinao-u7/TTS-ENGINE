package com.novelreader.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.tts.android.service.AudioFocusMode

/**
 * Stateless modal bottom sheet for adjusting audio focus and interruption settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsSheet(
    audioFocusMode: AudioFocusMode,
    keepReadingWhenOtherAudioStarts: Boolean,
    onModeSelected: (AudioFocusMode) -> Unit,
    onKeepReadingChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Audio Focus Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "When other audio is playing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            val modes = listOf(
                Triple(
                    AudioFocusMode.PAUSE_OTHERS,
                    "Pause others (default)",
                    "Pause other apps while reading (AUDIOFOCUS_GAIN)"
                ),
                Triple(
                    AudioFocusMode.DUCK_OTHERS,
                    "Duck others",
                    "Lower other audio volume across sentences (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)"
                ),
                Triple(
                    AudioFocusMode.MIX,
                    "Mix",
                    "Play alongside other audio without requesting focus"
                )
            )

            modes.forEach { (modeOption, title, description) ->
                val isSelected = audioFocusMode == modeOption
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onModeSelected(modeOption) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                            )
                            Text(
                                text = description,
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

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
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
                            text = "Keep reading when other audio starts",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ignore focus loss from other apps. Always pauses for phone calls and ringtones.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = keepReadingWhenOtherAudioStarts,
                        onCheckedChange = onKeepReadingChanged
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
