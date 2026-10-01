package com.novastore.app.core.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.novastore.app.core.ui.R

/**
 * QR scanner (ZXing, works without Google services). Returns a function that
 * opens the camera; [onScanned] receives the raw text of the code.
 */
@Composable
fun rememberQrScanner(onScanned: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let(onScanned)
    }
    val prompt = stringResource(R.string.search_scan_prompt)
    return remember(launcher, prompt) {
        {
            launcher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(prompt)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
    }
}
