package com.novastore.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novastore.app.core.ui.R as UiR

/**
 * First-launch storage permission request. Android 11+ gates "all files
 * access" behind a dedicated Settings toggle (no runtime dialog exists), so
 * the flow is: a Nova dialog explains WHY → "Allow" opens the exact system
 * screen → the user flips one switch and returns. On Android 10 and below a
 * regular runtime permission dialog is launched directly.
 *
 * Asked exactly ONCE per install (both outcomes count as "asked"); the app
 * keeps working without it — downloads land in the private cache either way.
 */
@Composable
fun StoragePermissionGate() {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_STORAGE_ASKED, false)) return@LaunchedEffect
        if (hasStorageAccess()) {
            // Already granted (e.g. restored install): never nag again.
            prefs.edit().putBoolean(KEY_STORAGE_ASKED, true).apply()
            return@LaunchedEffect
        }
        visible = true
    }

    if (!visible) return

    fun markAsked() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STORAGE_ASKED, true)
            .apply()
    }

    AlertDialog(
        onDismissRequest = {
            visible = false
            markAsked()
        },
        icon = {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(UiR.string.storage_permission_title)) },
        text = { Text(stringResource(UiR.string.storage_permission_text)) },
        confirmButton = {
            TextButton(onClick = {
                visible = false
                markAsked()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    legacyLauncher.launch(
                        arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                        ),
                    )
                }
            }) {
                Text(stringResource(UiR.string.storage_permission_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                visible = false
                markAsked()
            }) {
                Text(stringResource(UiR.string.storage_permission_later))
            }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val PREFS = "nova_permissions"
private const val KEY_STORAGE_ASKED = "storage_asked"
