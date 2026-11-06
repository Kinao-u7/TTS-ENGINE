package com.novelreader.tts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.tts.core.model.Character
import com.novelreader.tts.core.model.VoiceGender
import com.novelreader.tts.core.model.VoiceInfo
import java.util.Locale

/**
 * Stateless modal bottom sheet for editing a character's name, voice, speed, pitch, and narrator settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditSheet(
    character: Character,
    availableVoices: List<VoiceInfo>,
    onSave: (Character) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onPreviewVoice: ((Character) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var editedName by remember(character.id) { mutableStateOf(character.name) }
    var selectedVoiceId by remember(character.id) { mutableStateOf(character.voiceId) }
    var selectedGender by remember(character.id) { mutableStateOf(character.voiceGender) }
    var editedSpeed by remember(character.id) { mutableFloatStateOf(character.speed) }
    var editedPitch by remember(character.id) { mutableFloatStateOf(character.pitch) }
    var readByNarrator by remember(character.id) { mutableStateOf(character.readByNarrator) }

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
                text = if (character.isBuiltIn) "Edit ${character.name} Settings" else "Edit Character",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Name field
            OutlinedTextField(
                value = editedName,
                onValueChange = { if (!character.isBuiltIn) editedName = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !character.isBuiltIn,
                supportingText = {
                    if (character.isBuiltIn) Text("Built-in character names cannot be changed")
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Voice Picker
            VoicePicker(
                selectedVoiceId = selectedVoiceId,
                availableVoices = availableVoices,
                onVoiceSelected = { selectedVoiceId = it }
            )

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
            if (character.id != Character.NARRATOR_ID) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Read by narrator",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Lines spoken by this character will use the narrator's voice",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = readByNarrator,
                        onCheckedChange = { readByNarrator = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Actions: Delete (if deletable) and Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (character.isDeletable && onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
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
                        val updatedAliases = if (!trimmedNewName.equals(character.name, ignoreCase = true)) {
                            (character.aliases + character.name).distinctBy { it.lowercase() }
                        } else {
                            character.aliases
                        }
                        val updated = character.copy(
                            name = trimmedNewName,
                            aliases = updatedAliases,
                            voiceId = selectedVoiceId,
                            voiceGender = selectedGender,
                            speed = editedSpeed,
                            pitch = editedPitch,
                            readByNarrator = readByNarrator,
                            autoCreated = false
                        )
                        onSave(updated)
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
