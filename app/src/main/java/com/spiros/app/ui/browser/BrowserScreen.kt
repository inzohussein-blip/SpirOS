package com.spiros.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.spiros.app.R
import com.spiros.app.shield.Blocklist
import com.spiros.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val HOME = "https://duckduckgo.com/"

/**
 * Private browser: hardened WebView, trackers blocked with the Shield list, no
 * third-party cookies, no location/camera/mic, HTTPS upgrade, links to other apps
 * blocked, and every trace (cookies, storage, cache, history) burned on exit or lock.
 * Downloads are encrypted straight into the vault.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vault: Vault, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var blocked by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    fun download(url: String, contentDisposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val cookies = CookieManager.getInstance().getCookie(url)
        val agent = webView?.settings?.userAgentString
        message(context.getString(R.string.browser_downloading, name))
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    try {
                        cookies?.let { connection.setRequestProperty("Cookie", it) }
                        agent?.let { connection.setRequestProperty("User-Agent", it) }
                        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                        val type = mime?.takeIf { it.isNotBlank() } ?: connection.contentType ?: "application/octet-stream"
                        connection.inputStream.use { vault.files.importStream(it, name, type.substringBefore(';')) }
                    } finally {
                        connection.disconnect()
                    }
                }.isSuccess
            }
            message(context.getString(if (ok) R.string.browser_saved_to_vault else R.string.browser_download_failed, name))
        }
    }

    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("https://") -> text
            text.startsWith("http://") -> "https://" + text.removePrefix("http://")
            ' ' !in text && '.' in text -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
        }
        webView?.loadUrl(url)
    }

    DisposableEffect(Unit) {
        burn(context, null)
        onDispose {
            burn(context, webView)
            webView?.destroy()
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_back))
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { go(address) }),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                IconButton(onClick = {
                    burn(context, webView)
                    blocked = 0
                    webView?.loadUrl(HOME)
                    message(context.getString(R.string.browser_burned))
                }) {
                    Icon(Icons.Rounded.LocalFireDepartment, contentDescription = stringResource(R.string.browser_burn), tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.browser_forward))
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.browser_reload))
                }
                Text(
                    stringResource(R.string.browser_blocked, blocked),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    Blocklist.ensureLoaded(ctx)
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            setGeolocationEnabled(false)
                            safeBrowsingEnabled = false // would send visited URLs (hashed) to Google
                            mediaPlaybackRequiresUserGesture = true
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            // Look like a normal mobile browser instead of an embedded WebView.
                            userAgentString = userAgentString.replace("; wv", "")
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val host = request.url.host ?: return null
                                if (!request.isForMainFrame && Blocklist.isBlocked(host)) {
                                    view.post { blocked++ }
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                return null
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                return when (url.scheme) {
                                    "https" -> false
                                    "http" -> {
                                        view.loadUrl(url.buildUpon().scheme("https").build().toString())
                                        true
                                    }
                                    else -> true // intent:, tel:, market:, ... never leave the private browser
                                }
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                address = url.orEmpty()
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                                address = url.orEmpty()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                                callback.invoke(origin, false, false)
                            }

                            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                        }
                        setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                            download(url, contentDisposition, mimetype)
                        }
                        loadUrl(HOME)
                        webView = this
                    }
                },
            )
        }
        SnackbarHost(snackbar)
    }
}

/** Erase every trace the browser can leave. */
internal fun burn(context: Context, view: WebView?) {
    view?.apply {
        stopLoading()
        clearHistory()
        clearCache(true)
        clearFormData()
    }
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
}

