package com.novelreader.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.VoiceInfo

/**
 * Stateless modal bottom sheet for assigning a character/speaker to a dialogue line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignSpeakerSheet(
    segmentText: String,
    characters: List<Character>,
    availableVoices: List<VoiceInfo>,
    selectedSpeakerId: String,
    onSpeakerSelected: (Character) -> Unit,
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
                text = "Assign Speaker to Dialogue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = segmentText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn {
                items(characters) { char ->
                    val isSelected = selectedSpeakerId == char.id

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSpeakerSelected(char) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
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
