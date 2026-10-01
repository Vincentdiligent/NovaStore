package com.novastore.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novastore.app.core.ui.theme.LocalNovaAccent
import com.novastore.app.core.ui.theme.OnEmerald

/**
 * The signature Nova Store accent: a horizontal gradient of the currently
 * selected accent palette (e.g. emerald → teal). Bright white content goes
 * on top of it.
 */
fun novaAccentBrush(start: Color, end: Color): Brush = Brush.horizontalGradient(listOf(start, end))

/** Accent gradient from an explicit pair. */
fun novaAccentBrushFrom(accent: NovaAccentPair): Brush = Brush.horizontalGradient(listOf(accent.start, accent.end))

/** Immutable pair of accent colors. */
data class NovaAccentPair(val start: Color, val end: Color)

/** Current accent pair supplied by the theme. */
@Composable
fun novaAccentColors(): NovaAccentPair =
    LocalNovaAccent.current.let { NovaAccentPair(it.start, it.end) }

/** Accent gradient honoring the user-selected palette. */
@Composable
fun novaAccentBrush(): Brush = novaAccentBrushFrom(novaAccentColors())

/**
 * Hero banner background: a rich diagonal gradient derived from the accent
 * palette — vivid on top, deep at the bottom. Used for the app-details
 * header and large highlight cards.
 */
fun novaHeroBrush(start: Color, end: Color): Brush = Brush.linearGradient(
    listOf(
        start.copy(alpha = 0.92f),
        end.copy(alpha = 0.78f),
        end.copy(alpha = 0.42f),
    ),
)

/** Hero gradient honoring the user-selected palette. */
@Composable
fun novaHeroBrush(): Brush = novaHeroBrush(novaAccentColors().start, novaAccentColors().end)

/**
 * Primary call-to-action button with the Nova gradient (accent start → end).
 * 52dp tall, 20dp corner radius, white-on-gradient text — bright, never dark.
 */
@Composable
fun NovaGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val accent = novaAccentColors()
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = OnEmerald,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    novaAccentBrushFrom(accent).takeIf { enabled } ?: Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ),
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = if (enabled) OnEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
            } else if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = if (enabled) OnEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
