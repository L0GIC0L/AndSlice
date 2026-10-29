package io.github.l0gic0l.andslice.monitor

import io.github.l0gic0l.andslice.octoprint.PrinterStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PrintProgressTest {
    private fun status(
        state: String,
        printing: Boolean = false,
        paused: Boolean = false,
        completion: Double? = null,
        left: Long? = null,
        elapsed: Long? = null,
        message: String? = null,
    ) = PrinterStatus(state, printing, paused, !printing && !paused, null, null, "gcodes/body.gcode", completion, elapsed, left, message)

    @Test
    fun summarizesTheRunningPrint() {
        assertEquals("42% · 1h 5m left", PrintProgress.summary(status("Printing", printing = true, completion = 42.4, left = 3900)))
        assertEquals("Paused at 42%", PrintProgress.summary(status("Paused", paused = true, completion = 42.0)))
        assertEquals("Starting", PrintProgress.summary(status("Printing", printing = true)))
    }

    @Test
    fun tellsHowThePrintEnded() {
        val running = status("Printing", printing = true, completion = 63.0, elapsed = 7980)
        // Moonraker names the end state.
        assertEquals(PrintOutcome.Finished, PrintProgress.outcome(running, status("Complete", completion = 100.0)))
        assertEquals(PrintOutcome.Canceled, PrintProgress.outcome(running, status("Cancelled")))
        assertEquals(PrintOutcome.Failed, PrintProgress.outcome(running, status("Error", message = "Heater extruder not heating")))
        // OctoPrint goes back to Operational: done at 100%, stopped short of it.
        assertEquals(PrintOutcome.Finished, PrintProgress.outcome(running, status("Operational", completion = 100.0)))
        assertEquals(PrintOutcome.Stopped, PrintProgress.outcome(running, status("Operational", completion = 63.0)))

        assertEquals("body.gcode took 2h 13m", PrintProgress.ending(PrintOutcome.Finished, running, status("Complete")))
        assertEquals("body.gcode stopped at 63%", PrintProgress.ending(PrintOutcome.Canceled, running, status("Cancelled")))
        assertEquals(
            "Heater extruder not heating",
            PrintProgress.ending(PrintOutcome.Failed, running, status("Error", message = "Heater extruder not heating")),
        )
    }
}
