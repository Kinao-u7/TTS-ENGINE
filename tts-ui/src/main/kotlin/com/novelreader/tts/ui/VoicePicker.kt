package com.novelreader.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.tts.core.model.VoiceInfo

/**
 * Stateless voice selection dropdown.
 */
@Composable
fun VoicePicker(
    selectedVoiceId: String?,
    availableVoices: List<VoiceInfo>,
    onVoiceSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Assigned Voice",
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val currentSelectedVoice = availableVoices.firstOrNull { it.id == selectedVoiceId }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentSelectedVoice?.let { "${it.name} (${formatReadableLocale(it.locale)})" }
                            ?: "Auto-assigned (matches gender)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Select voice",
                        tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Auto-assign (Default)") },
                    onClick = {
                        onVoiceSelected(null)
                        expanded = false
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
                            onVoiceSelected(voice.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
