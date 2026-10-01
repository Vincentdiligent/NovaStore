package com.novastore.app.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novastore.app.core.model.AuthMethod
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.NovaGradientButton
import com.novastore.app.core.ui.components.SourceBadge
import com.novastore.app.core.ui.components.novaAccentBrush
import com.novastore.app.core.ui.components.novaAccentColors
import com.novastore.app.core.ui.components.novaHeroBrush
import com.novastore.app.core.ui.theme.OnEmerald
import com.novastore.app.domain.repository.AccountState

/**
 * Account, v4: the Nova Anonymous Engine (own development — no third-party
 * login services) is the primary, recommended flow; device-account and
 * Google App-Password logins are secondary options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPasswordForm by rememberSaveable { mutableStateOf(false) }
    var showWebLogin by rememberSaveable { mutableStateOf(false) }
    // Web sign-in finished but Google's page did not reveal the address.
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var pendingEmail by remember { mutableStateOf("") }

    if (showWebLogin) {
        GoogleWebLoginDialog(
            onToken = { mail, token ->
                showWebLogin = false
                if (mail.contains('@')) {
                    viewModel.loginWithWebToken(mail, token)
                } else {
                    pendingEmail = email
                    pendingToken = token
                }
            },
            onDismiss = { showWebLogin = false },
        )
    }
    pendingToken?.let { token ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingToken = null },
            title = { Text(stringResource(UiR.string.account_enter_email)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = pendingEmail,
                    onValueChange = { pendingEmail = it },
                    singleLine = true,
                    placeholder = { Text("name@gmail.com") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pendingEmail.contains('@'),
                    onClick = {
                        pendingToken = null
                        viewModel.loginWithWebToken(pendingEmail.trim(), token)
                    },
                ) { Text(stringResource(UiR.string.action_ok)) }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(UiR.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(UiR.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NovaLogoHeader()

            // Errors can now only come from the *optional* Google sign-in
            // paths — the anonymous engine itself never fails.
            if (state.error != null) {
                ErrorBanner(
                    text = state.error ?: "",
                    mentionsAppPassword = state.error?.contains("password", ignoreCase = true) == true ||
                        state.error?.contains("2fa", ignoreCase = true) == true ||
                        state.error?.contains("2-step", ignoreCase = true) == true,
                    onDismiss = viewModel::dismissError,
                )
            }

            when (val account = state.accountState) {
                is AccountState.SignedIn -> SignedInCard(
                    email = account.email,
                    method = account.method,
                    deviceProfile = state.deviceProfile,
                    busy = state.busy,
                    onSignOut = { viewModel.signOut() },
                )

                AccountState.Anonymous -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AnonymousEngineActiveCard(
                        busy = state.busy,
                        onReset = { viewModel.signOut() },
                    )
                    // Signing in stays available while anonymous.
                    SecondaryAccountSection(
                        accounts = state.deviceAccounts,
                        busy = state.busy,
                        showPasswordForm = showPasswordForm,
                        onTogglePasswordForm = { showPasswordForm = !showPasswordForm },
                        onDeviceLogin = { viewModel.loginWithDeviceAccount(it) },
                        onWebLogin = { showWebLogin = true },
                        email = email,
                        password = password,
                        onEmailChange = { email = it },
                        onPasswordChange = { password = it },
                        onSignIn = { viewModel.login(email, password) },
                    )
                }

                AccountState.NotSignedIn -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // --- Primary: Nova Anonymous Engine (recommended) ---
                    NovaAnonymousEngineCard(
                        busy = state.busy,
                        onContinue = { viewModel.loginAnonymously() },
                    )

                    // --- Secondary: own Google account (optional) ---
                    SecondaryAccountSection(
                        accounts = state.deviceAccounts,
                        busy = state.busy,
                        showPasswordForm = showPasswordForm,
                        onTogglePasswordForm = { showPasswordForm = !showPasswordForm },
                        onDeviceLogin = { viewModel.loginWithDeviceAccount(it) },
                        onWebLogin = { showWebLogin = true },
                        email = email,
                        password = password,
                        onEmailChange = { email = it },
                        onPasswordChange = { password = it },
                        onSignIn = { viewModel.login(email, password) },
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NovaLogoHeader() {
    val accent = novaAccentColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Brush.linearGradient(listOf(accent.start, accent.end)), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "Nova Store",
            style = TextStyle(
                brush = Brush.horizontalGradient(listOf(accent.start, accent.end)),
            ),
            fontSize = MaterialTheme.typography.displaySmall.fontSize,
            fontWeight = MaterialTheme.typography.displaySmall.fontWeight,
            fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
            letterSpacing = MaterialTheme.typography.displaySmall.letterSpacing,
        )
    }
}

/**
 * The primary card: Nova's own anonymous access engine. Three tiers —
 * Nova Web Catalog, community mirror, F-Droid — no account, never fails.
 */
@Composable
private fun NovaAnonymousEngineCard(
    busy: Boolean,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(novaHeroBrush())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column {
                Text(
                    text = stringResource(UiR.string.account_anonymous_engine),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    text = stringResource(UiR.string.account_anonymous_engine_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        EngineTierRow(
            icon = Icons.Filled.Language,
            title = stringResource(UiR.string.sources_web_catalog),
            description = stringResource(UiR.string.account_tier_web_catalog_desc),
        )
        EngineTierRow(
            icon = Icons.Filled.CloudDownload,
            title = stringResource(UiR.string.sources_mirror),
            description = stringResource(UiR.string.account_tier_mirror_desc),
        )
        EngineTierRow(
            icon = Icons.Filled.Android,
            title = stringResource(UiR.string.sources_fdroid_repos),
            description = stringResource(UiR.string.account_tier_fdroid_desc),
        )

        Text(
            text = stringResource(UiR.string.account_anonymous_note),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )

        // Bright call to action on top of the gradient (inverse surface).
        Surface(
            onClick = onContinue,
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(UiR.string.account_anonymous),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** One engine tier inside the gradient card (white-on-gradient content). */
@Composable
private fun EngineTierRow(icon: ImageVector, title: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.size(18.dp),
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Secondary options: passwordless device-account login and the (optional)
 * e-mail + App Password sign-in form.
 */
@Composable
private fun SecondaryAccountSection(
    accounts: List<String>,
    busy: Boolean,
    showPasswordForm: Boolean,
    onTogglePasswordForm: () -> Unit,
    onDeviceLogin: (String) -> Unit,
    onWebLogin: () -> Unit,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(UiR.string.account_signin_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(UiR.string.account_signin_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Primary: Google's own sign-in page — works with 2-Step Verification,
        // no App Password, no Google account on the phone required.
        androidx.compose.material3.Button(
            onClick = onWebLogin,
            enabled = !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(UiR.string.account_google_web_button))
        }
        Text(
            text = stringResource(UiR.string.account_google_web_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (accounts.isEmpty()) {
            Text(
                text = stringResource(UiR.string.account_no_device_accounts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(UiR.string.account_pick_account),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            accounts.forEach { account ->
                OutlinedButton(
                    onClick = { onDeviceLogin(account) },
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        account,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = stringResource(UiR.string.account_device_login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = onTogglePasswordForm) {
            Text(
                stringResource(UiR.string.account_toggle_email_form),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (showPasswordForm) {
            SignInForm(
                email = email,
                password = password,
                busy = busy,
                onEmailChange = onEmailChange,
                onPasswordChange = onPasswordChange,
                onSignIn = onSignIn,
            )
        }
    }
}

@Composable
private fun SignInForm(
    email: String,
    password: String,
    busy: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(UiR.string.account_email)) },
            leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(UiR.string.account_password)) },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    stringResource(UiR.string.account_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )

        NovaGradientButton(
            text = stringResource(UiR.string.account_sign_in),
            onClick = onSignIn,
            enabled = email.isNotBlank() && password.isNotBlank(),
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Shown when the account-less engine is active (AccountState.Anonymous). */
@Composable
private fun AnonymousEngineActiveCard(
    busy: Boolean,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(novaAccentBrush(), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = OnEmerald,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column {
                Text(
                    text = stringResource(UiR.string.account_fdroid_only),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(UiR.string.account_fdroid_only_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = stringResource(UiR.string.account_fdroid_only_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ActiveTierRow(stringResource(UiR.string.sources_web_catalog))
        ActiveTierRow(stringResource(UiR.string.sources_mirror))
        ActiveTierRow(stringResource(UiR.string.sources_fdroid_repos))

        OutlinedButton(
            onClick = onReset,
            enabled = !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { Text(stringResource(UiR.string.account_reset)) }
    }
}

@Composable
private fun ActiveTierRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBanner(
    text: String,
    mentionsAppPassword: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (mentionsAppPassword) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(UiR.string.account_error_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignedInCard(
    email: String,
    method: AuthMethod,
    deviceProfile: String,
    busy: Boolean,
    onSignOut: () -> Unit,
) {
    val accent = novaAccentColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Brush.linearGradient(listOf(accent.start, accent.end)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = email.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.background,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(UiR.string.account_signed_in_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SourceBadge(source = if (method == AuthMethod.GOOGLE_PASSWORD) "play" else "play")
        }

        InfoRow(
            icon = Icons.Filled.Lock,
            label = stringResource(UiR.string.account_method),
            value = stringResource(
                when (method) {
                    AuthMethod.GOOGLE_PASSWORD, AuthMethod.GOOGLE_WEB -> UiR.string.account_method_google
                    AuthMethod.ANONYMOUS_POOL -> UiR.string.account_method_anonymous
                    AuthMethod.DEVICE_ACCOUNT -> UiR.string.account_method_device
                },
            ),
        )
        if (deviceProfile.isNotBlank()) {
            InfoRow(
                icon = Icons.Filled.Smartphone,
                label = stringResource(UiR.string.account_device_profile),
                value = deviceProfile.removeSuffix(".properties"),
            )
        }

        OutlinedButton(
            onClick = onSignOut,
            enabled = !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(UiR.string.account_sign_out),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f),
        )
    }
}
