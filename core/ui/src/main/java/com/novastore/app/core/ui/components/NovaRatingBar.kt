package com.novastore.app.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novastore.app.core.ui.theme.Amber

/**
 * Five-star rating row (spec signature: value + size). Half stars are
 * rendered for fractional values (3.5 -> 3 full + 1 half + 1 outline).
 * Draws nothing when [value] is null or not in (0, 5].
 */
@Composable
fun NovaRatingBar(
    value: Float?,
    modifier: Modifier = Modifier,
    starSize: Dp = 14.dp,
    spacing: Dp = 1.dp,
    tint: Color = Amber,
) {
    if (value == null || value <= 0f || value > 5f) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        for (index in 0 until 5) {
            val starValue = value - index
            val icon = when {
                starValue >= 0.75f -> Icons.Filled.Star
                starValue >= 0.25f -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarOutline
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (starValue >= 0.25f) tint else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/** Rating bar plus the numeric value, e.g. "4.6". Draws nothing for null. */
@Composable
fun NovaRatingRow(
    value: Float?,
    modifier: Modifier = Modifier,
    starSize: Dp = 14.dp,
    tint: Color = Amber,
    numericStyle: TextStyle? = null,
    centered: Boolean = false,
) {
    if (value == null || value <= 0f || value > 5f) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        NovaRatingBar(value = value, starSize = starSize, tint = tint)
        Spacer(Modifier.size(4.dp))
        Text(
            text = "%.1f".format(value),
            style = numericStyle ?: MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Never wrap: in narrow grid lanes the five-star bar already eats
            // most of the width, and a wrapping number turns into a vertical
            // character column under the stars.
            softWrap = false,
            maxLines = 1,
        )
    }
}

/**
 * Play-Store style compact rating for narrow grid cells: one star + the
 * numeric value, single line, no wrapping. Used where the five-star row
 * does not fit (4-column grids on regular phones).
 */
@Composable
fun NovaRatingCompact(
    value: Float?,
    modifier: Modifier = Modifier,
    starSize: Dp = 12.dp,
    tint: Color = Amber,
) {
    if (value == null || value <= 0f || value > 5f) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(starSize),
        )
        Text(
            text = "%.1f".format(value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            maxLines = 1,
        )
    }
}

/** Formats a download count like 1234567 into "1.2M+". */
fun formatDownloadCount(downloads: Long?): String? {
    if (downloads == null || downloads <= 0) return null
    return when {
        downloads >= 1_000_000_000 -> "%.1fB+".format(downloads / 1_000_000_000f)
        downloads >= 1_000_000 -> "%.1fM+".format(downloads / 1_000_000f)
        downloads >= 1_000 -> "%.1fK+".format(downloads / 1_000f)
        else -> downloads.toString()
    }
}
