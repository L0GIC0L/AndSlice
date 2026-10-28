package io.github.l0gic0l.andslice

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.andslice.octoprint.DiscoveredServer
import io.github.l0gic0l.andslice.octoprint.Heater
import io.github.l0gic0l.andslice.octoprint.OctoPrintClient
import io.github.l0gic0l.andslice.octoprint.OctoPrintException
import io.github.l0gic0l.andslice.octoprint.OctoPrintVersion
import io.github.l0gic0l.andslice.octoprint.PrinterHost
import io.github.l0gic0l.andslice.octoprint.PrinterStatus
import io.github.l0gic0l.andslice.octoprint.Webcam
import io.github.l0gic0l.andslice.octoprint.discoverOctoPrint
import io.github.l0gic0l.andslice.monitor.PrintMonitorService
import io.github.l0gic0l.andslice.printers.PrinterStore
import io.github.l0gic0l.andslice.printers.SavedPrinter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The saved printers, and the active one's connection (OctoPrint or Moonraker):
 * live status, camera, uploads, job and machine control.
 */
class PrinterViewModel(app: Application) : AndroidViewModel(app) {

    var printers by mutableStateOf<List<SavedPrinter>>(emptyList()); private set
    var activeId by mutableStateOf(""); private set
    val active: SavedPrinter? get() = printers.firstOrNull { it.id == activeId }

    val url: String get() = active?.url.orEmpty()
    val apiKey: String get() = active?.apiKey.orEmpty()
    /** Moonraker (and so Rinkhals) usually needs no API key, so the address alone is enough. */
    val configured: Boolean get() = url.isNotBlank()

    var version by mutableStateOf<OctoPrintVersion?>(null); private set
    var status by mutableStateOf<PrinterStatus?>(null); private set
    var webcam by mutableStateOf<Webcam?>(null); private set
    /** Why the last status request failed, or null when it worked. */
    var connectionError by mutableStateOf<String?>(null); private set

    var testing by mutableStateOf(false); private set
    var testResult by mutableStateOf<String?>(null); private set

    /** Upload progress 0..1 while a file is being sent, else null. */
    var uploadProgress by mutableStateOf<Float?>(null); private set

    var discovering by mutableStateOf(false); private set
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList()); private set

    var message by mutableStateOf<String?>(null)

    /** Counts requests to show the printer screen, e.g. from the print notification. */
    var showRequests by mutableIntStateOf(0); private set

    fun requestShow() {
        showRequests++
    }

    /** Set when a print could be followed in a notification, but Android does not allow them yet. */
    var wantsNotificationPermission by mutableStateOf(false)

    init {
        val state = PrinterStore.load(app)
        printers = state.printers
        activeId = state.activeId
    }

    /** The detected server; found again after a failure, in case the printer was off. */
    private var host: PrinterHost? = null

    private suspend fun client(): PrinterHost = host ?: PrinterHost.connect(url, apiKey).also { host = it }

    // ------------------------------------------------------------------ saved printers

    private fun store() = PrinterStore.save(getApplication(), PrinterStore.State(printers, activeId))

    /** Drops everything learned about the previous connection. */
    private fun resetConnection() {
        host = null
        version = null
        status = null
        webcam = null
        connectionError = null
        testResult = null
    }

    fun setActive(id: String) {
        if (id == activeId || printers.none { it.id == id }) return
        activeId = id
        store()
        resetConnection()
    }

    fun addPrinter(printer: SavedPrinter) {
        printers = printers + printer
        activeId = printer.id
        store()
        resetConnection()
    }

    /** Replaces the saved printer with the same id. */
    fun updatePrinter(printer: SavedPrinter) {
        val old = printers.firstOrNull { it.id == printer.id } ?: return
        printers = printers.map { if (it.id == printer.id) printer else it }
        store()
        if (printer.id == activeId && (old.url != printer.url || old.apiKey != printer.apiKey || old.webcamUrl != printer.webcamUrl)) {
            resetConnection()
        }
    }

    /** Removes a saved printer; the last one cannot be removed. */
    fun removePrinter(id: String) {
        if (printers.size <= 1) return
        printers = printers.filter { it.id != id }
        if (activeId == id) {
            activeId = printers.first().id
            resetConnection()
        }
        store()
    }

    /** Changes the profile of the active printer, e.g. to another nozzle variant. */
    fun setActivePreset(preset: String) {
        active?.let { if (it.preset != preset) updatePrinter(it.copy(preset = preset)) }
    }

    fun saveConnection(newUrl: String, newKey: String, newWebcam: String = active?.webcamUrl.orEmpty()) {
        val printer = active ?: return
        updatePrinter(
            printer.copy(
                url = if (newUrl.isBlank()) "" else OctoPrintClient.normalizeUrl(newUrl),
                apiKey = newKey.trim(),
                webcamUrl = newWebcam.trim(),
            )
        )
        resetConnection()
    }

    fun forgetConnection() = saveConnection("", "", "")

    // ------------------------------------------------------------------ status

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
            val c = client()
            if (version == null) version = c.version()
            if (webcam == null) webcam = findWebcam(c)
            val s = c.status()
            status = s
            connectionError = null
            // A print started elsewhere, such as on the printer's screen, gets a notification too.
            if (s.printing || s.paused) followPrint(s)
        } catch (e: OctoPrintException) {
            connectionError = e.message
            status = null
            host = null
        }
    }

    /** The address set for the printer, or what the server reports. */
    private suspend fun findWebcam(c: PrinterHost): Webcam? {
        val manual = active?.webcamUrl.orEmpty()
        if (manual.isNotEmpty()) {
            val isSnapshot = "snapshot" in manual || manual.endsWith(".jpg", true)
            return Webcam(streamUrl = manual.takeUnless { isSnapshot }, snapshotUrl = manual.takeIf { isSnapshot })
        }
        return try {
            c.webcam()
        } catch (e: OctoPrintException) {
            null
        }
    }

    // ------------------------------------------------------------------ jobs

    /** Follows the print of the active printer in a notification, unless that is already happening. */
    private fun followPrint(status: PrinterStatus? = null) {
        val printer = active ?: return
        if (PrintMonitorService.following.value == printer.id) return
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences(PreferencesViewModel.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PreferencesViewModel.KEY_PRINT_NOTIFICATIONS, true)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            wantsNotificationPermission = true
            return
        }
        PrintMonitorService.start(context, printer, status)
    }

    /** Android allowed notifications: follow the print that is running. */
    fun onNotificationsAllowed() {
        if (status?.let { it.printing || it.paused } == true) followPrint(status)
    }

    fun upload(file: File, print: Boolean) {
        if (!configured || uploadProgress != null) return
        uploadProgress = 0f
        val name = active?.name ?: "the printer"
        // Started now, while the app is in front; it waits for the print to begin.
        if (print) followPrint()
        viewModelScope.launch {
            try {
                client().upload(file, print) { p -> uploadProgress = p }
                message = if (print) "Sent ${file.name} to $name and started the print." else "Uploaded ${file.name} to $name."
                refreshStatus()
            } catch (e: OctoPrintException) {
                message = e.message
                if (print) PrintMonitorService.stop(getApplication())
            } finally {
                uploadProgress = null
            }
        }
    }

    fun pauseOrResume() = command { client().pauseOrResume() }

    fun cancelPrint() = command { client().cancel() }

    // ------------------------------------------------------------------ machine control

    fun setTemperature(heater: Heater, celsius: Int) = command { client().setTemperature(heater, celsius) }

    /** Heats hotend and bed together, as a preheat button does; 0 turns both off. */
    fun preheat(hotend: Int, bed: Int) = command {
        client().setTemperature(Heater.Hotend, hotend)
        client().setTemperature(Heater.Bed, bed)
    }

    fun home() = command("Homing…") { client().home() }

    fun jog(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) = command { client().jog(x, y, z) }

    fun sendGcode(script: String) {
        if (script.isBlank()) return
        command("Sent.") { client().sendGcode(script.trim()) }
    }

    fun emergencyStop() = command("Emergency stop sent.") { client().emergencyStop() }

    private fun command(started: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                if (started != null) message = started
                block()
                refreshStatus()
            } catch (e: OctoPrintException) {
                message = e.message
            }
        }
    }

    // ------------------------------------------------------------------ discovery

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
}
