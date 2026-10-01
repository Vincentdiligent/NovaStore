package com.novastore.app.feature.details

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.formatDownloadCount
import com.novastore.app.core.ui.components.formatFileSize
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Additional information": version, dates, size, price, purchases, ads, SDKs, package. */
@Composable
internal fun AdditionalInfoSection(details: RemoteAppDetails, state: AppDetailsUiState) {
    val app = details.app
    val latest = state.bestVersion ?: details.versions.maxByOrNull { it.versionCode }
    SectionCard(title = stringResource(UiR.string.details_more_info)) {
        latest?.versionName?.let { InfoRow(stringResource(UiR.string.details_row_version), it) }
        state.installed?.versionName?.let { InfoRow(stringResource(UiR.string.details_row_installed_version), it) }
        val updated = details.uploadDate
            ?: (details.updatedMillis ?: app.updatedMillis)?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
        updated?.let { InfoRow(stringResource(UiR.string.details_row_updated), it) }
        details.releasedMillis?.let {
            InfoRow(stringResource(UiR.string.details_row_released), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))
        }
        (latest?.size ?: app.sizeBytes)?.let { InfoRow(stringResource(UiR.string.details_row_size), formatFileSize(it)) }
        formatDownloadCount(app.downloads)?.let { InfoRow(stringResource(UiR.string.details_row_downloads), it) }
        details.ratingCount?.let { InfoRow(stringResource(UiR.string.details_row_ratings), "%,d".format(it)) }
        InfoRow(
            stringResource(UiR.string.details_row_price),
            if (app.isFree) stringResource(UiR.string.details_value_free) else details.price ?: stringResource(UiR.string.details_value_paid),
        )
        // Play product details (in-app purchases, content rating notes…).
        details.productInfo.forEach { (label, value) -> InfoRow(label, value) }
        if (app.containsAds) {
            InfoRow(stringResource(UiR.string.details_row_ads), stringResource(UiR.string.details_value_contains_ads))
        }
        latest?.minSdk?.let { InfoRow(stringResource(UiR.string.details_row_min_android), androidName(it)) }
        latest?.targetSdk?.let { InfoRow(stringResource(UiR.string.details_row_target_sdk), androidName(it)) }
        app.license?.let { InfoRow(stringResource(UiR.string.details_row_license), it) }
        app.categories.firstOrNull()?.let { InfoRow(stringResource(UiR.string.details_stat_category), it) }
        InfoRow(stringResource(UiR.string.details_row_package), app.packageName)
    }
}

/** Developer contacts: name, website, e-mail, postal address — all actionable. */
@Composable
internal fun DeveloperSection(details: RemoteAppDetails) {
    val website = details.website?.takeIf { it.isNotBlank() }
    val email = details.developerEmail?.takeIf { it.isNotBlank() }
    val address = details.developerAddress?.takeIf { it.isNotBlank() }
    val source = details.sourceCodeUrl?.takeIf { it.isNotBlank() }
    if (website == null && email == null && address == null && source == null) return
    val context = LocalContext.current
    SectionCard(title = stringResource(UiR.string.details_developer)) {
        details.app.developer?.let {
            Text(it, style = MaterialTheme.typography.titleSmall)
        }
        website?.let { url ->
            ContactRow(Icons.Filled.Language, stringResource(UiR.string.details_dev_website), url) {
                open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        source?.let { url ->
            ContactRow(Icons.Filled.Language, stringResource(UiR.string.details_dev_source), url) {
                open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        email?.let { mail ->
            ContactRow(Icons.Filled.Email, stringResource(UiR.string.details_dev_email), mail) {
                open(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$mail")))
            }
        }
        address?.let { addr ->
            ContactRow(Icons.Filled.LocationOn, stringResource(UiR.string.details_dev_address), addr) {
                open(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(addr.replace('\n', ' ')))))
            }
        }
    }
}

/**
 * Dependencies (Play Services, shared libraries, companion packages) with
 * their state on THIS device — useful on phones without Google services.
 */
@Composable
internal fun DependenciesSection(dependencies: List<String>) {
    if (dependencies.isEmpty()) return
    val context = LocalContext.current
    val installed by produceState(initialValue = emptySet<String>(), dependencies) {
        value = withContext(Dispatchers.IO) { dependencies.filter { isInstalled(context, it) }.toSet() }
    }
    SectionCard(title = stringResource(UiR.string.details_dependencies)) {
        dependencies.forEach { pkg ->
            val present = pkg in installed
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (present) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(dependencyLabel(pkg), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = pkg + " · " + stringResource(
                            if (present) UiR.string.details_dep_installed else UiR.string.details_dep_missing,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if ("com.google.android.gms" in dependencies && "com.google.android.gms" !in installed) {
            Text(
                stringResource(UiR.string.details_dep_gms_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Permissions: the Play list, or — for installed apps without one — the
 * permissions the package really requests. Human labels from the system,
 * dangerous ones marked.
 */
@Composable
internal fun PermissionsSection(details: RemoteAppDetails, installedPackage: String?) {
    val context = LocalContext.current
    val permissions by produceState(initialValue = details.permissions, details.permissions, installedPackage) {
        if (details.permissions.isEmpty() && installedPackage != null) {
            value = withContext(Dispatchers.IO) { requestedPermissions(context, installedPackage) }
        }
    }
    if (permissions.isEmpty()) return
    val described by produceState(initialValue = emptyList<PermissionUi>(), permissions) {
        value = withContext(Dispatchers.IO) { permissions.map { describe(context, it) }.sortedBy { !it.dangerous } }
    }
    var expanded by remember(permissions) { mutableStateOf(false) }
    SectionCard(title = stringResource(UiR.string.details_permissions_count, permissions.size)) {
        val shown = if (expanded) described else described.take(PERMISSIONS_PREVIEW)
        shown.forEach { p ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (p.dangerous) Icons.Filled.Lock else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (p.dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).size(if (p.dangerous) 16.dp else 8.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        p.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (described.size > PERMISSIONS_PREVIEW) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(stringResource(if (expanded) UiR.string.details_less else UiR.string.details_show_all))
            }
        }
    }
}

@Composable
private fun ContactRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class PermissionUi(val name: String, val label: String, val dangerous: Boolean)

private const val PERMISSIONS_PREVIEW = 6

private fun describe(context: Context, permission: String): PermissionUi {
    val pm = context.packageManager
    return runCatching {
        val info = pm.getPermissionInfo(permission, 0)
        val label = info.loadLabel(pm).toString().replaceFirstChar { it.uppercase() }
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.protection else {
            @Suppress("DEPRECATION")
            info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
        }
        PermissionUi(permission, label, base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS)
    }.getOrElse {
        PermissionUi(permission, permission.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, false)
    }
}

private fun requestedPermissions(context: Context, packageName: String): List<String> = runCatching {
    context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
}.getOrDefault(emptyList())

private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
    context.packageManager.getPackageInfo(packageName, 0)
    true
}.getOrDefault(false)

private fun dependencyLabel(pkg: String): String = when (pkg) {
    "com.google.android.gms" -> "Google Play Services"
    "com.android.vending" -> "Google Play Store"
    "com.google.android.webview" -> "Android System WebView"
    "com.google.android.trichromelibrary" -> "Trichrome Library"
    "com.google.android.tts" -> "Speech Services by Google"
    "com.google.ar.core" -> "Google Play Services for AR"
    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

private fun androidName(sdk: Int): String {
    val release = when (sdk) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"
        28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
        else -> null
    }
    return if (release != null) "Android $release (API $sdk)" else "API $sdk"
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
