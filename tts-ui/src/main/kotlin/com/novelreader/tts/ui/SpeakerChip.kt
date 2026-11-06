package com.novelreader.tts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.tts.core.playback.SpeakerChipInfo

/**
 * Stateless speaker chip displaying the currently active speaker.
 */
@Composable
fun SpeakerChip(
    speakerName: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "Speaker: $speakerName",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Overload accepting [SpeakerChipInfo].
 */
@Composable
fun SpeakerChip(
    speaker: SpeakerChipInfo?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    if (speaker != null) {
        SpeakerChip(
            speakerName = speaker.displayName,
            modifier = modifier,
            onClick = onClick
        )
    }
}
