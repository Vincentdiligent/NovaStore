package com.novastore.app.feature.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ThumbUpAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import com.novastore.app.core.model.AppReview
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.DownloadState
import com.novastore.app.core.model.RatingHistogram
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.model.SOURCE_APKCOMBO
import com.novastore.app.core.model.SOURCE_APKPURE
import com.novastore.app.core.model.SOURCE_PLAY
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.AppIcon
import com.novastore.app.core.ui.components.ErrorState
import com.novastore.app.core.ui.components.InstalledAppIcon
import com.novastore.app.core.ui.components.LoadingState
import com.novastore.app.core.ui.components.NovaGradientButton
import com.novastore.app.core.ui.components.NovaRatingBar
import com.novastore.app.core.ui.components.SourceBadge
import com.novastore.app.core.ui.components.formatDownloadCount
import com.novastore.app.core.ui.components.formatFileSize
import com.novastore.app.core.ui.components.novaAccentBrush
import com.novastore.app.core.ui.components.novaHeroBrush
import com.novastore.app.core.ui.theme.OnEmerald
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailsScreen(
    packageName: String,
    onBack: () -> Unit,
    viewModel: AppDetailsViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(packageName) { viewModel.load(packageName) }
    // Re-sync installed state every time the screen comes back to the
    // foreground — i.e. right after the system install/uninstall dialog.
    androidx.lifecycle.compose.LifecycleResumeEffect(packageName) {
        viewModel.refreshInstalledState()
        onPauseOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadTask by viewModel.downloadTask.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var viewerUrl by remember { mutableStateOf<String?>(null) }

    fun shareApp(name: String) {
        val text = context.getString(
            UiR.string.details_share_text,
            name,
            "https://play.google.com/store/apps/details?id=$packageName",
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(UiR.string.details_share_via)))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.details?.app?.name ?: state.preview?.name ?: state.installed?.appName ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // No back arrow: the app's own icon sits in the top-left corner
                // (system back gesture / button navigates back).
                navigationIcon = {
                    val app = state.details?.app ?: state.preview
                    Box(modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                        if (app?.iconUrl != null) {
                            AppIcon(
                                packageName = app.packageName,
                                appName = app.name,
                                iconUrl = app.iconUrl,
                                fallbackIconUrl = app.altIconUrl ?: state.preview?.iconUrl,
                                size = 36.dp,
                                shape = RoundedCornerShape(10.dp),
                            )
                        } else {
                            InstalledAppIcon(
                                packageName = packageName,
                                appName = app?.name ?: state.installed?.appName ?: packageName,
                                size = 36.dp,
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                },
                actions = {
                    val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
                    if (state.details != null || state.installed != null) {
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = if (favorite) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = stringResource(
                                    if (favorite) UiR.string.details_favorite_remove else UiR.string.details_favorite_add,
                                ),
                                tint = if (favorite) androidx.compose.ui.graphics.Color(0xFFE5486B) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.loading && state.preview != null -> PreviewHeader(padding = padding, app = state.preview!!)
            state.loading -> LoadingState(Modifier.padding(padding), stringResource(UiR.string.details_loading))
            state.details != null -> DetailsContent(
                padding = padding,
                viewModel = viewModel,
                state = state,
                downloadTask = downloadTask,
                onShare = { shareApp(state.details?.app?.name ?: packageName) },
                onOpenScreenshot = { url -> viewerUrl = url },
            )
            state.installed != null -> LocalOnlyContent(
                padding = padding,
                viewModel = viewModel,
                state = state,
            )
            else -> ErrorState(
                Modifier.padding(padding),
                title = stringResource(UiR.string.details_not_tracked_title),
                description = state.error
                    ?: stringResource(UiR.string.details_not_tracked_desc),
                retryLabel = stringResource(UiR.string.cd_back),
                onRetry = onBack,
            )
        }
    }

    viewerUrl?.let { url ->
        val shots = state.details?.screenshots ?: emptyList()
        ScreenshotViewerDialog(
            screenshots = shots,
            initialIndex = shots.indexOf(url).coerceAtLeast(0),
            onDismiss = { viewerUrl = null },
        )
    }
}

/**
 * REAL download progress for the install flow: a determinate bar with
 * percent, transferred/total bytes and live speed while the engine
 * reports DOWNLOADING; the honest indeterminate bar only covers phases
 * without measurable progress (mirror resolution, verification, the
 * PackageInstaller session itself).
 */
@Composable
private fun DownloadProgressPanel(task: DownloadTaskInfo?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Local copy: DownloadTaskInfo lives in another Gradle module, so its
        // properties never smart-cast across the null check.
        val totalBytes = task?.totalBytes
        val activelyTransferring = task?.state == DownloadState.DOWNLOADING &&
            totalBytes != null && totalBytes > 0
        if (activelyTransferring && task != null) {
            LinearProgressIndicator(
                progress = { task.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            val speed = task.speedBytesPerSec?.let { " · ${formatFileSize(it)}/s" } ?: ""
            Text(
                text = stringResource(
                    UiR.string.details_progress_downloading,
                    task.progressPercent,
                    formatFileSize(task.downloadedBytes),
                    formatFileSize(totalBytes),
                ) + speed,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (task?.state == DownloadState.DOWNLOADING || task?.state == DownloadState.VERIFYING) {
            // Transferring without a known total (or verifying): keep the
            // indeterminate bar but still show live byte counts.
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                text = stringResource(
                    UiR.string.details_progress_bytes,
                    formatFileSize(task.downloadedBytes),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                text = stringResource(UiR.string.details_busy),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailsContent(
    padding: PaddingValues,
    viewModel: AppDetailsViewModel,
    state: AppDetailsUiState,
    downloadTask: DownloadTaskInfo?,
    onShare: () -> Unit,
    onOpenScreenshot: (String) -> Unit,
) {
    val details = state.details ?: return
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BannerHeader(
            details = details,
            onOpen = if (state.isInstalled) ({ viewModel.openApp() }) else null,
        )

        // --- Primary action row ---
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.busy) {
                DownloadProgressPanel(task = downloadTask)
            } else {
                // Opening lives in the card's top-right corner; here only
                // Install / Update (or the unavailable state) remain. An
                // installed, current app needs no big button at all.
                if (!(state.isInstalled && state.action == DetailsAction.UP_TO_DATE))
                NovaGradientButton(
                    text = when (state.action) {
                        DetailsAction.INSTALL -> stringResource(UiR.string.details_install) +
                            (state.bestVersion?.versionName?.let { " · $it" } ?: "")
                        DetailsAction.UPDATE -> stringResource(UiR.string.details_update) +
                            (state.bestVersion?.versionName?.let { " · $it" } ?: "")
                        DetailsAction.UP_TO_DATE -> stringResource(UiR.string.details_up_to_date)
                        DetailsAction.UNAVAILABLE -> stringResource(UiR.string.details_unavailable)
                    },
                    onClick = { viewModel.installOrUpdate() },
                    enabled = state.action == DetailsAction.INSTALL || state.action == DetailsAction.UPDATE,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = if (state.action == DetailsAction.UP_TO_DATE) {
                        { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = OnEmerald, modifier = Modifier.size(20.dp)) }
                    } else {
                        null
                    },
                )
                if (state.unavailableReason == UnavailableReason.PAID) {
                    val playContext = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val pkg = details.app.packageName
                            val market = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=$pkg"),
                            ).setPackage("com.android.vending")
                            runCatching { playContext.startActivity(market) }.onFailure {
                                runCatching {
                                    playContext.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                                        ),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(stringResource(UiR.string.details_open_play))
                    }
                } else if (state.action == DetailsAction.UNAVAILABLE) {
                    val browserContext = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            // Honest last resort: the system browser opens the
                            // mirror search — Nova never fakes an in-app
                            // download flow it cannot deliver.
                            runCatching {
                                browserContext.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                            "https://apkpure.com/search?q=" +
                                                java.net.URLEncoder.encode(details.app.packageName),
                                        ),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(UiR.string.details_mirror_browser))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SecondaryActionButton(
                        icon = Icons.Filled.IosShare,
                        label = stringResource(UiR.string.details_share),
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.isInstalled) {
                        SecondaryActionButton(
                            icon = Icons.Filled.Delete,
                            label = stringResource(UiR.string.details_uninstall),
                            onClick = { viewModel.uninstall() },
                            modifier = Modifier.weight(1f),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        SecondaryActionButton(
                            icon = Icons.Filled.Settings,
                            label = stringResource(UiR.string.details_app_info),
                            onClick = { viewModel.openAppSettings() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            val reasonText = state.unavailableReason?.let { reason ->
                stringResource(
                    when (reason) {
                        UnavailableReason.NO_VERSIONS -> UiR.string.details_unavailable_no_versions
                        UnavailableReason.INCOMPATIBLE -> UiR.string.details_unavailable_incompatible
                        UnavailableReason.SIGNATURE -> UiR.string.details_unavailable_signature
                        UnavailableReason.PAID -> UiR.string.details_unavailable_paid
                    },
                )
            }
            if (reasonText != null) {
                InfoBanner(
                    text = reasonText,
                    container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            val errorText = state.errorRes?.let { stringResource(it) } ?: state.error
            if (errorText != null) {
                InfoBanner(
                    text = errorText,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            state.notice?.let { notice ->
                // User-cancelled installs are neutral events — surface,
                // not error red / success green.
                val neutral = notice is DetailsNotice.InstallCancelled
                InfoBanner(
                    text = noticeText(notice),
                    container = if (neutral) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    content = if (neutral) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }
        }

        // --- Ratings & reviews: ONE merged section (summary + histogram + reviews) ---
        if (details.app.rating != null || details.ratingHistogram != null || details.ratingCount != null ||
            state.reviews.isNotEmpty() || state.reviewsLoading
        ) {
            // Breathing room before the section — it used to sit too
            // tight under the Share / Uninstall / App info button row.
            Spacer(Modifier.height(10.dp))
            RatingReviewsSection(
                rating = details.app.rating,
                ratingCount = details.ratingCount,
                histogram = details.ratingHistogram,
                reviews = state.reviews,
                reviewsLoading = state.reviewsLoading,
            )
        }

        // --- Photos / screenshots ---
        if (details.screenshots.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(UiR.string.details_screenshots),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(details.screenshots) { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = stringResource(UiR.string.cd_screenshot),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(200.dp)
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onOpenScreenshot(url) },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // --- About ---
        if (!details.description.isNullOrBlank()) {
            SectionCard(title = stringResource(UiR.string.details_about)) {
                val description = details.description ?: ""
                var expanded by remember(description) { mutableStateOf(false) }
                var overflowing by remember(description) { mutableStateOf(false) }
                val annotated = if (description.contains('<')) {
                    AnnotatedString.fromHtml(description)
                } else {
                    AnnotatedString(description)
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                )
                if (overflowing || expanded) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(stringResource(if (expanded) UiR.string.details_less else UiR.string.details_more))
                    }
                }
            }
        }

        // --- What's new ---
        if (!details.changelog.isNullOrBlank()) {
            SectionCard(title = stringResource(UiR.string.details_whats_new)) {
                // Play sends release notes as HTML (<br>, <b>…): render it,
                // never show raw tags.
                val changelog = details.changelog ?: ""
                Text(
                    text = if (changelog.contains('<') || changelog.contains("&")) {
                        AnnotatedString.fromHtml(changelog)
                    } else {
                        AnnotatedString(changelog)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // --- Versions ---
        if (details.versions.isNotEmpty()) {
            val mirrorLinkVisible = details.app.source == SOURCE_PLAY ||
                details.versions.any { it.source == SOURCE_APKPURE || it.source == SOURCE_APKCOMBO }
            SectionCard(
                title = stringResource(UiR.string.details_versions),
                trailing = if (mirrorLinkVisible) {
                    {
                        val browserContext = LocalContext.current
                        TextButton(
                            onClick = {
                                runCatching {
                                    browserContext.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(
                                                "https://apkpure.com/search?q=" +
                                                    java.net.URLEncoder.encode(details.app.packageName),
                                            ),
                                        ),
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                text = stringResource(UiR.string.details_mirror_browser),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    null
                },
            ) {
                val best = state.bestVersion
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    details.versions
                        .sortedByDescending { it.versionCode }
                        .take(8)
                        .forEach { version ->
                            VersionRow(
                                version = version,
                                selected = best?.versionCode == version.versionCode,
                                installed = state.installed?.versionCode == version.versionCode,
                            )
                        }
                }
            }
        }

        // --- Privacy & security ---
        SectionCard(title = stringResource(UiR.string.details_privacy)) {
            val latest = state.bestVersion ?: details.versions.maxByOrNull { it.versionCode }
            InfoRow(stringResource(UiR.string.details_row_source), latest?.source ?: details.app.source)
            InfoRow(stringResource(UiR.string.details_row_license), details.app.license ?: "—")
            latest?.signer?.let { InfoRow(stringResource(UiR.string.details_row_signer), "${it.take(16)}…") }
            latest?.sha256?.let { InfoRow(stringResource(UiR.string.details_row_sha), "${it.take(16)}…") }
            InfoRow(
                stringResource(UiR.string.details_row_verification),
                stringResource(UiR.string.details_verification_value),
            )
        }

        // --- Additional information, dependencies, permissions, developer ---
        AdditionalInfoSection(details = details, state = state)
        DependenciesSection(dependencies = details.dependencies)
        PermissionsSection(details = details, installedPackage = state.installed?.packageName)
        DeveloperSection(details = details)

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun BannerHeader(details: RemoteAppDetails, onOpen: (() -> Unit)? = null) {
    val app = details.app
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(novaHeroBrush()),
    ) {
    if (onOpen != null) {
        // "Open" for installed apps: top-right corner of the card.
        Surface(
            onClick = onOpen,
            shape = CircleShape,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(UiR.string.details_open),
                    tint = Color(0xFF6965F1),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 18.dp, bottom = 18.dp, end = if (onOpen != null) 64.dp else 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(
                packageName = app.packageName,
                appName = app.name,
                iconUrl = app.iconUrl,
                fallbackIconUrl = app.altIconUrl,
                size = 96.dp,
                shape = RoundedCornerShape(28.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.developer ?: stringResource(UiR.string.details_unknown_developer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NovaRatingBar(
                    value = app.rating,
                    starSize = 16.dp,
                    tint = Color(0xFFFFE082),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Metadata strip: rating value, downloads, size, category. Pills
        // WRAP: a long category ("Cloud Storage File Sync", "Безопасность")
        // moves to a second line instead of being squeezed letter by letter.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatPill(
                value = app.rating?.let { "%.1f".format(it) } ?: "—",
                label = stringResource(UiR.string.details_stat_rating),
            )
            formatDownloadCount(app.downloads)?.let {
                StatPill(it, stringResource(UiR.string.details_stat_downloads))
            }
            app.sizeBytes?.let { StatPill(formatFileSize(it), stringResource(UiR.string.details_stat_size)) }
            app.categories.firstOrNull()?.let {
                StatPill(NovaCategoryLabel(it), stringResource(UiR.string.details_stat_category))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            SourceBadge(source = app.source)
        }
        val dateLine = details.updatedMillis?.let {
            stringResource(UiR.string.details_updated_on, formatVersionDate(it))
        } ?: details.releasedMillis?.let {
            stringResource(UiR.string.details_released_on, formatVersionDate(it))
        }
        dateLine?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

}

/** Translates a raw category name for display in the details header. */
@Composable
private fun NovaCategoryLabel(raw: String): String =
    com.novastore.app.core.ui.components.NovaCategories.label(raw)

@Composable
private fun StatPill(value: String, label: String) {
    Surface(
        color = Color.White.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Never wrap inside a pill: the pill grows, the row wraps instead.
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Merged ratings & reviews section: big value + stars + count, the 5→1 star
 * histogram, the first reviews and a "read all reviews" bottom sheet trigger.
 */
@Composable
private fun RatingReviewsSection(
    rating: Float?,
    ratingCount: Long?,
    histogram: RatingHistogram?,
    reviews: List<AppReview>,
    reviewsLoading: Boolean,
) {
    var allReviewsVisible by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(UiR.string.details_rating_title)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = rating?.let { "%.1f".format(it) } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NovaRatingBar(value = rating, starSize = 20.dp)
                ratingCount?.let {
                    Text(
                        text = stringResource(UiR.string.details_rating_count, "%,d".format(it)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (histogram != null) {
            // Small gap between the summary row and the histogram bars.
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val shares = histogram.shares()
                val counts = listOf(histogram.five, histogram.four, histogram.three, histogram.two, histogram.one)
                for (index in counts.indices) {
                    HistogramRow(stars = 5 - index, count = counts[index], share = shares[index])
                }
            }
        }
        if (reviews.isNotEmpty()) {
            // Thin separator between the rating summary and the review list.
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                reviews.take(3).forEach { review ->
                    ReviewItem(review = review)
                }
            }
            if (reviews.size > 3) {
                TextButton(
                    onClick = { allReviewsVisible = true },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        text = stringResource(
                            UiR.string.details_reviews_read_all_fmt,
                            "%,d".format(reviews.size),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        } else if (reviewsLoading) {
            // Reviews are being fetched and none are cached yet — reserve
            // their spot inside the same section.
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }

    if (allReviewsVisible) {
        AllReviewsSheet(
            reviews = reviews,
            onDismiss = { allReviewsVisible = false },
        )
    }
}

/** Bottom sheet listing every review; header with a close affordance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllReviewsSheet(
    reviews: List<AppReview>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(UiR.string.details_rating_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(UiR.string.cd_back),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 550.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            reviews.forEach { review ->
                ReviewItem(review = review)
            }
        }
    }
}

/** One compact histogram line: "5★ [▮▮▮▯▯] 12,345". */
@Composable
private fun HistogramRow(stars: Int, count: Long, share: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "$stars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(novaAccentBrush()),
            )
        }
        Text(
            text = "%,d".format(count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One user review: avatar, author, stars, date, collapsible text, developer reply. */
@Composable
private fun ReviewItem(review: AppReview) {
    var expanded by remember(review.id) { mutableStateOf(false) }
    var overflowing by remember(review.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReviewAvatar(author = review.author, avatarUrl = review.avatarUrl)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = review.author,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NovaRatingBar(value = review.rating.toFloat(), starSize = 12.dp)
                    // Date + targeted app version, e.g. "Sep 24, 2026 · v8.2.1".
                    val meta = buildString {
                        review.timestampMillis?.let { append(formatReviewDate(it)) }
                        review.reviewAppVersion?.let { version ->
                            if (isNotEmpty()) append(" · ")
                            append("v")
                            append(version)
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Text(
            text = review.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (overflowing || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) UiR.string.details_review_less else UiR.string.details_review_more,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        // "N people found this helpful" — thumbs-up count from the source.
        review.thumbsUpCount?.let { count ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ThumbUpAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        review.replyText?.let { reply ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(UiR.string.details_review_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = reply,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 36dp circular reviewer avatar with an initial-letter fallback. */
@Composable
private fun ReviewAvatar(author: String, avatarUrl: String?) {
    if (avatarUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(avatarUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = author.take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Compact localized review date, e.g. "Sep 24, 2026". */
private fun formatReviewDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

/** Requests the larger variant of Play-hosted screenshots when available. */
private fun hiResScreenshotUrl(url: String): String =
    if (url.contains("=w526-h296")) url.replace("=w526-h296", "=w1052-h592-rw") else url

/** Full-screen screenshot viewer with a horizontal pager. */
@Composable
private fun ScreenshotViewerDialog(
    screenshots: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (screenshots.isEmpty()) {
        onDismiss()
        return
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            val pagerState = rememberPagerState(initialPage = initialIndex) { screenshots.size }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = hiResScreenshotUrl(screenshots[page]),
                    contentDescription = stringResource(UiR.string.cd_screenshot),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${screenshots.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Surface(
                onClick = onDismiss,
                color = Color.Black.copy(alpha = 0.45f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(UiR.string.cd_back),
                    tint = Color.White,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

/** Translates a [DetailsNotice] into a localized message. */
@Composable
private fun noticeText(notice: DetailsNotice): String = when (notice) {
    is DetailsNotice.Installed -> stringResource(UiR.string.details_installed_ok, notice.version ?: "")
    DetailsNotice.NeedsConfirmation -> stringResource(UiR.string.details_needs_confirmation)
    DetailsNotice.InstallFailed -> stringResource(UiR.string.details_install_failed)
    DetailsNotice.InstallCancelled -> stringResource(UiR.string.details_install_cancelled)
    DetailsNotice.UninstallStarted -> stringResource(UiR.string.details_uninstall_started)
}

@Composable
private fun SecondaryActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoBanner(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
internal fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** Compact single-line version row: name · size · date, download icon. */
@Composable
private fun VersionRow(version: AppVersion, selected: Boolean, installed: Boolean) {
    val installedLabel = stringResource(UiR.string.details_version_installed)
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), CircleShape),
                )
            }
            Text(
                text = buildString {
                    append(version.versionName ?: "?")
                    append(" · ${formatFileSize(version.size)}")
                    version.addedAt?.let { append(" · ${formatVersionDate(it)}") }
                    if (installed) append(" · $installedLabel")
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Compact localized date for a version row. */
private fun formatVersionDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.62f),
        )
    }
}

/**
 * Installed app that is not in any enabled repository — no dead end:
 * open, uninstall and system settings are still available.
 */
@Composable
private fun LocalOnlyContent(
    padding: PaddingValues,
    viewModel: AppDetailsViewModel,
    state: AppDetailsUiState,
) {
    val installed = state.installed ?: return
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(novaHeroBrush())
                .padding(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                InstalledAppIcon(
                    packageName = installed.packageName,
                    appName = installed.appName,
                    size = 96.dp,
                    shape = RoundedCornerShape(28.dp),
                )
                Column {
                    Text(
                        text = installed.appName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${installed.versionName ?: "?"} (${installed.versionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                    Text(
                        text = installed.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }

        NovaGradientButton(
            text = stringResource(UiR.string.details_open_app),
            onClick = { viewModel.openApp() },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryActionButton(
                icon = Icons.Filled.Delete,
                label = stringResource(UiR.string.details_uninstall),
                onClick = { viewModel.uninstall() },
                modifier = Modifier.weight(1f),
                tint = MaterialTheme.colorScheme.error,
            )
            SecondaryActionButton(
                icon = Icons.Filled.Settings,
                label = stringResource(UiR.string.details_app_info),
                onClick = { viewModel.openAppSettings() },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.error != null) {
            InfoBanner(
                text = state.error ?: "",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        state.notice?.let { notice ->
            val neutral = notice is DetailsNotice.InstallCancelled
            InfoBanner(
                text = noticeText(notice),
                container = if (neutral) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                content = if (neutral) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
        }

        InfoBanner(
            text = stringResource(UiR.string.details_local_only_hint),
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** Instant header while the full page loads: what the list already knew. */
@Composable
private fun PreviewHeader(padding: PaddingValues, app: com.novastore.app.core.model.RemoteApp) {
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(novaHeroBrush())
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(
                packageName = app.packageName,
                appName = app.name,
                iconUrl = app.iconUrl,
                fallbackIconUrl = app.altIconUrl,
                size = 96.dp,
                shape = RoundedCornerShape(28.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                app.developer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 1) }
                NovaRatingBar(value = app.rating, starSize = 16.dp, tint = Color(0xFFFFE082))
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    }
}
