package com.novastore.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Application icon with a fallback chain: [iconUrl] → [fallbackIconUrl] →
 * letter avatar. A broken or dead icon link (e.g. a repository that moved
 * its icon files) never leaves an empty hole — the next source, and finally
 * the letter, takes over. [size] and [shape] allow squircle styling.
 */
@Composable
fun AppIcon(
    packageName: String,
    appName: String,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 48.dp,
    shape: Shape = CircleShape,
    fallbackIconUrl: String? = null,
) {
    val candidates = remember(iconUrl, fallbackIconUrl) {
        listOfNotNull(iconUrl?.takeIf { it.isNotBlank() }, fallbackIconUrl?.takeIf { it.isNotBlank() }).distinct()
    }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(attempt)
    if (url != null) {
        val px = with(LocalDensity.current) { size.roundToPx() }
        val context = LocalContext.current
        // The request is built once per (url, size), not on every recomposition.
        val request = remember(url, px) {
            ImageRequest.Builder(context)
                .data(url)
                // Decode EXACTLY at display size: icon CDNs serve 512×512+
                // bitmaps; decoding dozens of those per grid is scroll jank.
                .size(px)
                // No crossfade for small icons during fling.
                .crossfade(false)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription ?: appName,
            contentScale = ContentScale.Crop,
            onError = { attempt++ },
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = appName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Formats a byte count into a human readable string. */
fun formatFileSize(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024f)
    if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024f * 1024f))
    return "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
}
