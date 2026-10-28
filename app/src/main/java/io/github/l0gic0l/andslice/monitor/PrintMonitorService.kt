package io.github.l0gic0l.andslice.monitor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.l0gic0l.andslice.MainActivity
import io.github.l0gic0l.andslice.PreferencesViewModel
import io.github.l0gic0l.andslice.R
import io.github.l0gic0l.andslice.octoprint.OctoPrintException
import io.github.l0gic0l.andslice.octoprint.PrinterHost
import io.github.l0gic0l.andslice.octoprint.PrinterStatus
import io.github.l0gic0l.andslice.octoprint.Webcam
import io.github.l0gic0l.andslice.octoprint.grabCameraFrame
import io.github.l0gic0l.andslice.printers.PrinterStore
import io.github.l0gic0l.andslice.printers.SavedPrinter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follows a print on a saved printer while the app is in the background: an ongoing notification
 * with the progress, time left and a camera snapshot, then an alert when the print ends.
 *
 * A foreground service of the connected device type, since it talks to the printer over the
 * network for as long as the print takes, which can be more than a day.
 */
class PrintMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var printer: SavedPrinter? = null
    private var host: PrinterHost? = null
    /** Poll again now, e.g. after pause or resume. */
    private var wake: Job? = null
    /** The progress notification on show. */
    private var shown: android.app.Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedPrint = printer?.let { key(it, lastStatus) }
                finish()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_RESUME -> {
                scope.launch {
                    try {
                        host?.pauseOrResume()
                    } catch (e: OctoPrintException) {
                        Log.w(TAG, "Pause or resume failed", e)
                    }
                    wake?.cancel()
                }
                return START_NOT_STICKY
            }
        }
        val id = intent?.getStringExtra(EXTRA_PRINTER)
        val saved = PrinterStore.load(this).printers.firstOrNull { it.id == id }
        if (saved == null || saved.url.isBlank()) {
            finish()
            return START_NOT_STICKY
        }
        createChannels(this)
        // Android wants the notification within seconds of every start.
        val same = saved.id == printer?.id && job?.isActive == true
        val first = shown.takeIf { same } ?: progressNotification(saved, null, null, "Waiting for the print to start")
        try {
            // ServiceCompat drops the type before Android 10, which has none.
            @SuppressLint("InlinedApi")
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this, NOTIFICATION_PROGRESS, first, type)
        } catch (e: Exception) {
            // Not allowed from the background on Android 12 and later.
            Log.w(TAG, "Cannot follow the print", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!same) {
            job?.cancel()
            printer = saved
            host = null
            job = scope.launch { follow(saved) }
        }
        running.value = saved.id
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        running.value = null
        super.onDestroy()
    }

    private suspend fun follow(printer: SavedPrinter) {
        val started = System.currentTimeMillis()
        var last: PrinterStatus? = null
        var unreachableSince: Long? = null
        var webcam: Webcam? = null
        var picture: Bitmap? = null
        var pictureTime = 0L
        val snapshots = getSharedPreferences(PreferencesViewModel.PREFS, MODE_PRIVATE)
            .getBoolean(PreferencesViewModel.KEY_NOTIFICATION_SNAPSHOTS, true)

        suspend fun snapshot(h: PrinterHost): Bitmap? = withContext(Dispatchers.IO) {
            if (!snapshots) return@withContext null
            if (webcam == null) webcam = findWebcam(printer, h)
            webcam?.let { grabCameraFrame(it, printer.apiKey) }?.let(::decodeScaled)
        }

        while (true) {
            try {
                val h = host ?: PrinterHost.connect(printer.url, printer.apiKey).also { host = it }
                val status = h.status()
                unreachableSince = null
                when {
                    PrintProgress.active(status) -> {
                        val now = System.currentTimeMillis()
                        if (now - pictureTime > SNAPSHOT_INTERVAL_MS) {
                            picture = snapshot(h) ?: picture
                            pictureTime = now
                        }
                        last = status
                        lastStatus = status
                        notify(NOTIFICATION_PROGRESS, progressNotification(printer, status, picture, PrintProgress.summary(status)))
                    }
                    last != null -> {
                        // The print that was running has ended.
                        val outcome = PrintProgress.outcome(last, status)
                        notify(NOTIFICATION_DONE, doneNotification(printer, outcome, PrintProgress.ending(outcome, last, status), snapshot(h)))
                        break
                    }
                    System.currentTimeMillis() - started > START_TIMEOUT_MS -> break
                }
            } catch (e: OctoPrintException) {
                host = null
                val since = unreachableSince ?: System.currentTimeMillis().also { unreachableSince = it }
                if (System.currentTimeMillis() - since > UNREACHABLE_TIMEOUT_MS) {
                    notify(
                        NOTIFICATION_DONE,
                        doneNotification(printer, null, "Lost the connection to ${printer.name}. Open the printer screen to check on it.", null),
                    )
                    break
                }
                notify(NOTIFICATION_PROGRESS, progressNotification(printer, last, picture, "Cannot reach the printer, trying again"))
            }
            wake = scope.launch { delay(POLL_INTERVAL_MS) }
            wake?.join()
        }
        finish()
    }

    private suspend fun findWebcam(printer: SavedPrinter, host: PrinterHost): Webcam? {
        val manual = printer.webcamUrl
        if (manual.isNotEmpty()) return Webcam(streamUrl = manual, snapshotUrl = manual)
        return try {
            host.webcam()
        } catch (e: OctoPrintException) {
            null
        }
    }

    private fun finish() {
        job?.cancel()
        running.value = null
        lastStatus = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
    private fun notify(id: Int, notification: android.app.Notification) {
        if (id == NOTIFICATION_PROGRESS) shown = notification
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_PRINTER, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, PrintMonitorService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(printer: SavedPrinter, status: PrinterStatus?, picture: Bitmap?, text: String) =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(status?.fileName?.substringAfterLast('/')?.let { "Printing $it" } ?: "Print on ${printer.name}")
            .setContentText(text)
            .setSubText(printer.name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .apply {
                val percent = status?.completion
                if (status != null && PrintProgress.active(status)) {
                    setProgress(100, percent?.toInt() ?: 0, percent == null)
                    addAction(0, if (status.paused) "Resume" else "Pause", serviceAction(ACTION_PAUSE_RESUME, 1))
                }
                addAction(0, "Stop updates", serviceAction(ACTION_STOP, 2))
                if (picture != null) {
                    setLargeIcon(picture)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
                }
            }
            .build()

    private fun doneNotification(printer: SavedPrinter, outcome: PrintOutcome?, text: String, picture: Bitmap?) =
        NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(outcome?.title ?: "Print status unknown")
            .setContentText(text)
            .setSubText(printer.name)
            .setAutoCancel(true)
            .setCategory(if (outcome == PrintOutcome.Finished) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .apply {
                if (picture != null) {
                    setLargeIcon(picture)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?).setSummaryText(text))
                } else {
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
            }
            .build()

    companion object {
        private const val TAG = "PrintMonitor"
        private const val EXTRA_PRINTER = "printer"
        private const val ACTION_STOP = "io.github.l0gic0l.andslice.STOP_MONITOR"
        private const val ACTION_PAUSE_RESUME = "io.github.l0gic0l.andslice.PAUSE_RESUME"
        private const val CHANNEL_PROGRESS = "print_progress"
        private const val CHANNEL_DONE = "print_done"
        private const val NOTIFICATION_PROGRESS = 1
        private const val NOTIFICATION_DONE = 2
        private const val POLL_INTERVAL_MS = 10_000L
        private const val SNAPSHOT_INTERVAL_MS = 60_000L
        /** How long to wait for an uploaded print to start. */
        private const val START_TIMEOUT_MS = 10 * 60_000L
        /** How long the printer may be out of reach before the app gives up. */
        private const val UNREACHABLE_TIMEOUT_MS = 30 * 60_000L

        /** Id of the saved printer being followed, or null. */
        private val running = MutableStateFlow<String?>(null)
        val following: StateFlow<String?> get() = running

        @Volatile private var lastStatus: PrinterStatus? = null
        /** The print the user stopped following, so the app does not start again on it. */
        @Volatile private var stoppedPrint: String? = null

        private fun key(printer: SavedPrinter, status: PrinterStatus?) = printer.id + "/" + status?.fileName

        /**
         * Follows [printer]'s print, if print notifications are on and allowed. Call it while the app
         * is in the foreground: Android does not let background apps start this service.
         */
        fun start(context: Context, printer: SavedPrinter, status: PrinterStatus? = null) {
            if (!enabled(context) || printer.url.isBlank()) return
            if (status != null && key(printer, status) == stoppedPrint) return
            stoppedPrint = null
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, PrintMonitorService::class.java).putExtra(EXTRA_PRINTER, printer.id),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cannot follow the print", e)
            }
        }

        fun stop(context: Context) {
            if (running.value == null) return
            context.startService(Intent(context, PrintMonitorService::class.java).setAction(ACTION_STOP))
        }

        /** Print notifications are on in the app and allowed by Android. */
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PreferencesViewModel.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PreferencesViewModel.KEY_PRINT_NOTIFICATIONS, true) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Print progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of the print, while it runs"
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Print finished", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a print finishes, fails or is canceled"
                },
            )
        }

        /** A camera frame small enough for a notification. */
        private fun decodeScaled(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
