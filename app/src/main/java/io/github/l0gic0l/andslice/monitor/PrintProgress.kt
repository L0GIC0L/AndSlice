package io.github.l0gic0l.andslice.monitor

import io.github.l0gic0l.andslice.octoprint.PrinterStatus
import io.github.l0gic0l.andslice.ui.formatDuration
import kotlin.math.roundToInt

/** How a print the app followed came to an end. */
enum class PrintOutcome(val title: String) {
    Finished("Print finished"),
    Canceled("Print canceled"),
    Failed("Print failed"),
    /** It ended short of 100% without the server saying why, e.g. canceled on OctoPrint. */
    Stopped("Print stopped"),
}

/** The texts of the print notifications, from the printer's status. */
object PrintProgress {
    /** A job is running: printing or paused. */
    fun active(status: PrinterStatus) = status.printing || status.paused

    /**
     * Why the job that was [last] seen running is no longer running. Moonraker names the end
     * state ("complete", "cancelled", "error"); OctoPrint goes back to "Operational", at 100% after
     * a finished print.
     */
    fun outcome(last: PrinterStatus?, now: PrinterStatus): PrintOutcome = when {
        now.state.equals("complete", ignoreCase = true) -> PrintOutcome.Finished
        now.state.contains("cancel", ignoreCase = true) -> PrintOutcome.Canceled
        now.state.contains("error", ignoreCase = true) -> PrintOutcome.Failed
        (now.completion ?: last?.completion ?: 0.0) >= 99.5 -> PrintOutcome.Finished
        else -> PrintOutcome.Stopped
    }

    /** "42% · 1h 5m left", "Paused at 42%" or "Starting". */
    fun summary(status: PrinterStatus): String {
        val percent = status.completion?.let { "${it.roundToInt().coerceIn(0, 100)}%" }
        return when {
            status.paused -> "Paused" + (percent?.let { " at $it" } ?: "")
            percent == null -> "Starting"
            else -> percent + (status.printTimeLeftSeconds?.let { " · ${formatDuration(it.toDouble())} left" } ?: "")
        }
    }

    /** "body.gcode took 2h 13m", or what the printer says went wrong. */
    fun ending(outcome: PrintOutcome, last: PrinterStatus?, now: PrinterStatus): String {
        val file = (last?.fileName ?: now.fileName)?.substringAfterLast('/') ?: "The print"
        return when (outcome) {
            PrintOutcome.Finished -> file + (last?.printTimeSeconds?.let { " took ${formatDuration(it.toDouble())}" } ?: " is done")
            PrintOutcome.Failed -> now.message ?: "$file failed"
            PrintOutcome.Canceled, PrintOutcome.Stopped ->
                file + (last?.completion?.let { " stopped at ${it.roundToInt()}%" } ?: " stopped")
        }
    }
}
