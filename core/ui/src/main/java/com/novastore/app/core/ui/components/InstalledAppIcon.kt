package com.novastore.app.core.ui.components

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide cache of rasterized launcher icons ("pkg@px" → bitmap).
 * Sized in KB of bitmap memory; ~150 icons at 144 px fit comfortably.
 */
private val installedIconCache = object : LruCache<String, ImageBitmap>(12 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width * value.height * 4 / 1024).coerceAtLeast(1)
}

/**
 * Icon of a locally installed application, read straight from
 * PackageManager (the real launcher icon — no network involved).
 * Falls back to a letter avatar if the package can no longer be resolved.
 *
 * The drawable is rasterized ONCE, off the main thread, at exactly the
 * displayed pixel size, and cached — previously every recomposition during
 * a fling re-rendered a full-size adaptive icon on the UI thread.
 */
@Composable
fun InstalledAppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 48.dp,
    shape: Shape = CircleShape,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val key = "$packageName@$px"
    val icon = produceState(initialValue = installedIconCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(px, px)
                    .asImageBitmap()
                    .also { installedIconCache.put(key, it) }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: Throwable) {
                null
            }
        }
    }.value

    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = contentDescription ?: appName,
            contentScale = ContentScale.Crop,
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
