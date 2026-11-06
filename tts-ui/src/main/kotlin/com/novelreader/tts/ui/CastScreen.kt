package com.novelreader.tts.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.Segment
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.model.VoiceInfo

/**
 * Stateless character cast screen displaying configured and auto-created characters.
 */
@Composable
fun CastScreen(
    characters: List<Character>,
    availableVoices: List<VoiceInfo>,
    segments: List<Segment>,
    autoDetectSpeakers: Boolean,
    onAutoDetectSpeakersChange: (Boolean) -> Unit,
    onCharacterClick: (Character) -> Unit,
    onAddCharacterClick: () -> Unit,
    onRemoveAutoCreatedClick: () -> Unit,
    onPreviewVoice: (Character) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        val autoCreatedCount = characters.count { it.autoCreated }

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Character Cast",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (autoCreatedCount > 0) {
                        "${characters.size} characters ($autoCreatedCount auto-created)"
                    } else {
                        "${characters.size} characters configured in this book"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (autoCreatedCount > 0) {
                    OutlinedButton(
                        onClick = onRemoveAutoCreatedClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Remove auto-created")
                    }
                }

                Button(onClick = onAddCharacterClick) {
                    Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
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
            items(characters) { char ->
                val lineCount = segments.count { it.effectiveSpeakerId == char.id }
                val assignedVoice = availableVoices.firstOrNull { it.id == char.voiceId }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCharacterClick(char) },
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
                                } else if (char.autoCreated) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "(Auto-created)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
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
                        IconButton(onClick = { onPreviewVoice(char) }) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Preview voice",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stateless dialog for creating a new custom character.
 */
@Composable
fun AddCharacterDialog(
    onAdd: (Character) -> Unit,
    onDismissRequest: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newVoiceId by remember { mutableStateOf<String?>(null) }
    var newGender by remember { mutableStateOf(VoiceGender.ANY) }
    var newSpeed by remember { mutableFloatStateOf(1.0f) }
    var newPitch by remember { mutableFloatStateOf(1.0f) }
    var newReadByNarrator by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
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
                        onAdd(newChar)
                    }
                },
                enabled = newName.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
