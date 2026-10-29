package io.github.l0gic0l.andslice.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.github.l0gic0l.andslice.DownloadRequest
import io.github.l0gic0l.andslice.SlicerViewModel
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/** Sites with free models to print, and how to search them. */
enum class ModelSite(val title: String, val home: String, private val search: String) {
    Printables("Printables", "https://www.printables.com/model", "https://www.printables.com/search/models?q=%s"),
    Thingiverse("Thingiverse", "https://www.thingiverse.com/", "https://www.thingiverse.com/search?q=%s&type=things&sort=relevant"),
    MakerWorld("MakerWorld", "https://makerworld.com/en", "https://makerworld.com/en/search/models?keyword=%s"),
    Thangs("Thangs", "https://thangs.com/", "https://thangs.com/search/%s?scope=all"),
    Cults("Cults", "https://cults3d.com/en", "https://cults3d.com/en/search?q=%s"),
    ;

    fun searchUrl(query: String): String = search.format(URLEncoder.encode(query.trim(), "UTF-8"))
}

/** The browser's site, pages and history, kept while the app runs so it opens again where it was. */
class BrowserMemory {
    var site by mutableStateOf(ModelSite.Printables)
    var pages: Bundle? = null
}

/**
 * Model sites in a browser inside the app. A search box searches the chosen site, and files the
 * site downloads (STL, 3MF, OBJ or zip) are kept in Downloads/AndSlice and put on the plate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBrowserScreen(vm: SlicerViewModel, memory: BrowserMemory, onClose: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var siteMenu by remember { mutableStateOf(false) }
    BackHandler { if (canGoBack) web?.goBack() else onClose() }

    fun search() {
        if (query.isBlank()) return
        keyboard?.hide()
        web?.loadUrl(memory.site.searchUrl(query))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close the browser") } },
                title = {
                    Box {
                        Row(Modifier.clickable { siteMenu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(memory.site.title, maxLines = 1)
                            Icon(Icons.Default.ArrowDropDown, "Choose a site")
                        }
                        DropdownMenu(expanded = siteMenu, onDismissRequest = { siteMenu = false }) {
                            ModelSite.entries.forEach { site ->
                                DropdownMenuItem(
                                    text = { Text(site.title) },
                                    trailingIcon = if (site == memory.site) ({ Icon(Icons.Default.Check, null) }) else null,
                                    onClick = {
                                        siteMenu = false
                                        memory.site = site
                                        web?.loadUrl(if (query.isBlank()) site.home else site.searchUrl(query))
                                    },
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton(onClick = { web?.url?.let { openInBrowser(context, it) } }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in browser")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search ${memory.site.title}") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            vm.download?.let { (name, fraction) ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Downloading $name", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (fraction >= 0f) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                }
            }
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            WebPage(
                startUrl = memory.site.home,
                saved = memory.pages,
                onCreated = { web = it },
                onSave = { memory.pages = it },
                onProgress = { progress = it },
                onPageChanged = { canGoBack = it.canGoBack() },
                onDownload = { vm.downloadModel(context, it) },
                onBlob = { name, bytes -> vm.receiveDownload(context, name, bytes) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/** The printer's own web interface (Mainsail, Fluidd or OctoPrint) inside the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterWebScreen(url: String?, error: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler { if (canGoBack) web?.goBack() else onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close the web interface") } },
                title = {
                    Column {
                        Text("Web interface", maxLines = 1)
                        if (url != null) {
                            Text(
                                url,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { web?.reload() }, enabled = url != null) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton(onClick = { (web?.url ?: url)?.let { openInBrowser(context, it) } }, enabled = url != null) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in browser")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> Text(error, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                url == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    WebPage(
                        startUrl = url,
                        saved = null,
                        onCreated = { web = it },
                        onSave = {},
                        onProgress = { progress = it },
                        onPageChanged = { canGoBack = it.canGoBack() },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser installed.
    }
}

/**
 * A WebView that keeps its pages in [onSave] when it leaves the screen. Downloads go to
 * [onDownload]; files a page builds itself (blob: links) are read through JavaScript and go to
 * [onBlob] as bytes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPage(
    startUrl: String,
    saved: Bundle?,
    onCreated: (WebView) -> Unit,
    onSave: (Bundle) -> Unit,
    onProgress: (Int) -> Unit,
    onPageChanged: (WebView) -> Unit,
    modifier: Modifier = Modifier,
    onDownload: ((DownloadRequest) -> Unit)? = null,
    onBlob: ((String, ByteArray) -> Unit)? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.scheme == "http" || uri.scheme == "https") return false
                        // App links (intent:, market:, mailto:) open outside the app.
                        try {
                            val intent = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
                            // Only apps that open links, never a component the page names.
                            intent.addCategory(Intent.CATEGORY_BROWSABLE)
                            intent.component = null
                            intent.selector = null
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Nothing handles it.
                        }
                        return true
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onPageChanged(view)
                }
                if (onDownload != null) {
                    // Blob downloads are answered with a one-time token, so pages cannot push files unasked.
                    val pending = mutableMapOf<String, String>()
                    val main = Handler(Looper.getMainLooper())
                    if (onBlob != null) {
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun deliver(token: String, name: String, dataUrl: String) {
                                val fallback = synchronized(pending) { pending.remove(token) } ?: return
                                val bytes = try {
                                    Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
                                } catch (e: IllegalArgumentException) {
                                    return
                                }
                                if (bytes.isNotEmpty()) main.post { onBlob(name.ifBlank { fallback }, bytes) }
                            }
                        }, "AndSliceBlob")
                    }
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        if (url.startsWith("blob:")) {
                            if (onBlob == null) return@setDownloadListener
                            val token = UUID.randomUUID().toString()
                            synchronized(pending) { pending[token] = name }
                            evaluateJavascript(blobScript(url, token), null)
                        } else {
                            onDownload(DownloadRequest(url, name, userAgent, this.url))
                        }
                    }
                }
                if (saved == null || restoreState(saved) == null) loadUrl(startUrl)
                onCreated(this)
            }
        },
        onRelease = { view ->
            onSave(Bundle().also { view.saveState(it) })
            view.destroy()
        },
    )
}

/** Reads a blob: download in the page and hands it to AndSliceBlob, named as its link says. */
private fun blobScript(url: String, token: String): String {
    val u = JSONObject.quote(url)
    val t = JSONObject.quote(token)
    return """
        (function() {
          var link = document.querySelector('a[href=' + JSON.stringify($u) + ']');
          var name = (link && link.download) || '';
          var xhr = new XMLHttpRequest();
          xhr.open('GET', $u, true);
          xhr.responseType = 'blob';
          xhr.onload = function() {
            var reader = new FileReader();
            reader.onloadend = function() { AndSliceBlob.deliver($t, name, reader.result || ''); };
            reader.readAsDataURL(xhr.response);
          };
          xhr.send();
        })();
    """.trimIndent()
}
