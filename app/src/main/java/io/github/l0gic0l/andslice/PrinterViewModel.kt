package io.github.l0gic0l.andslice

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.andslice.octoprint.DiscoveredServer
import io.github.l0gic0l.andslice.octoprint.OctoPrintClient
import io.github.l0gic0l.andslice.octoprint.OctoPrintException
import io.github.l0gic0l.andslice.octoprint.OctoPrintVersion
import io.github.l0gic0l.andslice.octoprint.PrinterHost
import io.github.l0gic0l.andslice.octoprint.PrinterStatus
import io.github.l0gic0l.andslice.octoprint.discoverOctoPrint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Print server connection (OctoPrint or Moonraker): settings, live status, uploads and job control. */
class PrinterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("octoprint", Context.MODE_PRIVATE)

    var url by mutableStateOf(prefs.getString(KEY_URL, "") ?: ""); private set
    var apiKey by mutableStateOf(prefs.getString(KEY_API_KEY, "") ?: ""); private set
    /** Moonraker (and so Rinkhals) usually needs no API key, so the address alone is enough. */
    val configured: Boolean get() = url.isNotBlank()

    var version by mutableStateOf<OctoPrintVersion?>(null); private set
    var status by mutableStateOf<PrinterStatus?>(null); private set
    /** Why the last status request failed, or null when it worked. */
    var connectionError by mutableStateOf<String?>(null); private set

    var testing by mutableStateOf(false); private set
    var testResult by mutableStateOf<String?>(null); private set

    /** Upload progress 0..1 while a file is being sent, else null. */
    var uploadProgress by mutableStateOf<Float?>(null); private set

    var discovering by mutableStateOf(false); private set
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList()); private set

    var message by mutableStateOf<String?>(null)

    /** The detected server; found again after a failure, in case the printer was off. */
    private var host: PrinterHost? = null

    private suspend fun client(): PrinterHost = host ?: PrinterHost.connect(url, apiKey).also { host = it }

    fun saveConnection(newUrl: String, newKey: String) {
        url = OctoPrintClient.normalizeUrl(newUrl)
        apiKey = newKey.trim()
        prefs.edit {
            putString(KEY_URL, url)
            putString(KEY_API_KEY, apiKey)
        }
        host = null
        version = null
        status = null
        connectionError = null
        testResult = null
    }

    fun forgetConnection() {
        url = ""
        apiKey = ""
        prefs.edit { clear() }
        host = null
        version = null
        status = null
        connectionError = null
        testResult = null
    }

    /** Checks the given address and key without saving them. */
    fun testConnection(testUrl: String, testKey: String) {
        if (testing) return
        testing = true
        testResult = null
        viewModelScope.launch {
            testResult = try {
                val v = PrinterHost.connect(testUrl, testKey.trim()).version()
                "Connected to ${v.text}"
            } catch (e: OctoPrintException) {
                e.message
            } finally {
                testing = false
            }
        }
    }

    /** Refreshes the printer status every two seconds until the caller is cancelled. */
    suspend fun pollStatus() {
        while (configured) {
            refreshStatus()
            delay(2_000)
        }
    }

    suspend fun refreshStatus() {
        if (!configured) return
        try {
            if (version == null) version = client().version()
            status = client().status()
            connectionError = null
        } catch (e: OctoPrintException) {
            connectionError = e.message
            status = null
            host = null
        }
    }

    fun upload(file: File, print: Boolean) {
        if (!configured || uploadProgress != null) return
        uploadProgress = 0f
        viewModelScope.launch {
            try {
                client().upload(file, print) { p -> uploadProgress = p }
                message = if (print) "Sent ${file.name} to the printer and started the print." else "Uploaded ${file.name} to the printer."
                refreshStatus()
            } catch (e: OctoPrintException) {
                message = e.message
            } finally {
                uploadProgress = null
            }
        }
    }

    fun pauseOrResume() = command { client().pauseOrResume() }

    fun cancelPrint() = command { client().cancel() }

    private fun command(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                refreshStatus()
            } catch (e: OctoPrintException) {
                message = e.message
            }
        }
    }

    private var discoveryJob: Job? = null

    /** Looks for OctoPrint on the local network for a few seconds. */
    fun discover(context: Context) {
        discoveryJob?.cancel()
        discovered = emptyList()
        discovering = true
        discoveryJob = viewModelScope.launch {
            withTimeoutOrNull(8_000) {
                discoverOctoPrint(context.applicationContext)
                    .catch { e -> message = e.message }
                    .collect { server ->
                        if (discovered.none { it.url == server.url }) discovered = discovered + server
                    }
            }
            discovering = false
            if (discovered.isEmpty()) message = "No OctoPrint found on this network. Enter the printer's address instead."
        }
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_API_KEY = "api_key"
    }
}
