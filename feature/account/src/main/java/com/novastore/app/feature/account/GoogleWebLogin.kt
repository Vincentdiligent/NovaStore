package com.novastore.app.feature.account

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import com.novastore.app.core.ui.R as UiR

private const val EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup"
private const val OAUTH_COOKIE = "oauth_token"

/**
 * Google's own sign-in page, full screen. Google itself handles the
 * password, 2-Step Verification and device prompts; once the account is
 * signed in the page sets the `oauth_token` cookie, which is handed to
 * [onToken] together with the account e-mail.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleWebLoginDialog(
    onToken: (email: String, oauthToken: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var lastEmail by remember { mutableStateOf("") }
    var delivered by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(UiR.string.cd_back))
                }
                Text(
                    text = stringResource(UiR.string.account_google_web_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        // A clean slate: an old half-finished Google session
                        // must not leak into this sign-in.
                        val cookies = CookieManager.getInstance()
                        cookies.removeAllCookies(null)
                        cookies.flush()
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    loading = false
                                    // Remember the address the user typed on the
                                    // identifier step (needed for the token exchange).
                                    view.evaluateJavascript(EMAIL_PROBE) { raw ->
                                        val found = raw?.trim('"')?.takeIf { it.contains('@') }
                                        if (found != null) lastEmail = found
                                        val token = oauthCookie(url)
                                        if (token != null && !delivered) {
                                            delivered = true
                                            onToken(lastEmail, token)
                                        }
                                    }
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    loading = true
                                }
                            }
                            loadUrl(EMBEDDED_SETUP_URL)
                        }
                    },
                )
            }
        }
    }
}

private fun oauthCookie(url: String?): String? {
    val cookies = CookieManager.getInstance()
    val raw = listOfNotNull(url, "https://accounts.google.com")
        .mapNotNull { runCatching { cookies.getCookie(it) }.getOrNull() }
        .joinToString("; ")
    return raw.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$OAUTH_COOKIE=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }
}

/** Finds the signed-in / typed e-mail on Google's pages. */
private const val EMAIL_PROBE = """
(function() {
  var p = document.getElementById('profileIdentifier');
  if (p && p.innerText && p.innerText.indexOf('@') > 0) return p.innerText.trim();
  var i = document.querySelector('input[type=email]');
  if (i && i.value && i.value.indexOf('@') > 0) return i.value.trim();
  var d = document.querySelector('[data-email]');
  if (d) return d.getAttribute('data-email');
  var m = (document.body ? document.body.innerText : '').match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/);
  return m ? m[0] : '';
})();
"""
