package com.novastore.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novastore.app.core.model.SOURCE_APKCOMBO
import com.novastore.app.core.model.SOURCE_APKPURE
import com.novastore.app.core.model.SOURCE_FDROID
import com.novastore.app.core.model.SOURCE_GITHUB
import com.novastore.app.core.model.SOURCE_GITLAB
import com.novastore.app.core.model.SOURCE_PLAY

/**
 * Small chip identifying where an app comes from.
 * "Play" is emerald-tinted, "F-Droid" neutral, everything else gray.
 */
@Composable
fun SourceBadge(
    source: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (source.isNullOrBlank()) return
    val (label, container, content) = when (source.lowercase()) {
        SOURCE_PLAY -> Triple(
            "Play",
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.primary,
        )
        SOURCE_FDROID -> Triple(
            "F-Droid",
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SOURCE_GITHUB -> Triple(
            "GitHub",
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.tertiary,
        )
        SOURCE_GITLAB -> Triple(
            "GitLab",
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.tertiary,
        )
        SOURCE_APKPURE -> Triple(
            "Mirror",
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.secondary,
        )
        SOURCE_APKCOMBO -> Triple(
            "Mirror",
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.secondary,
        )
        else -> Triple(
            prettifySource(source),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 3.dp),
    )
}

/** "izzy_on_droid" -> "Izzy On Droid"; keeps repository ids readable. */
private fun prettifySource(source: String): String =
    source
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            if (word.length <= 3) word.uppercase() else word.replaceFirstChar { it.uppercase() }
        }
        .take(18)
