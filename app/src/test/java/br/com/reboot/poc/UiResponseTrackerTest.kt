
package br.com.reboot.poc

import br.com.reboot.poc.presentation.UiResponseTracker
import org.junit.Assert.*
import org.junit.Test

class UiResponseTrackerTest {

    @Test
    fun latestResponseIsAccepted() {
        val tracker = UiResponseTracker()

        val commandId = tracker.issue()

        assertTrue(
            tracker.shouldAccept(commandId)
        )
    }

    @Test
    fun oldResponseIsRejected() {
        val tracker = UiResponseTracker()

        val oldCommand = tracker.issue()
        val newCommand = tracker.issue()

        assertFalse(
            tracker.shouldAccept(oldCommand)
        )

        assertTrue(
            tracker.shouldAccept(newCommand)
        )
    }

    @Test
    fun delayedResponseCannotReplaceNewerState() {
        val tracker = UiResponseTracker()

        val statusQuery = tracker.issue()
        val cancelRequest = tracker.issue()

        assertTrue(
            tracker.shouldAccept(cancelRequest)
        )

        assertFalse(
            tracker.shouldAccept(statusQuery)
        )
    }

    @Test
    fun repeatedResponseDoesNotChangeOrder() {
        val tracker = UiResponseTracker()

        val commandId = tracker.issue()

        assertTrue(tracker.shouldAccept(commandId))
        assertTrue(tracker.shouldAccept(commandId))
    }
}
