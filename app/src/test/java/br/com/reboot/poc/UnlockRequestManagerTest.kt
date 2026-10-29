package br.com.reboot.poc

import br.com.reboot.poc.domain.unlock.UnlockDelay
import br.com.reboot.poc.domain.unlock.UnlockRequestManager
import br.com.reboot.poc.domain.unlock.UnlockRequestState
import org.junit.Assert.*
import org.junit.Test

class UnlockRequestManagerTest {

    private var now = 100_000L

    private fun manager() =
        UnlockRequestManager { now }

    @Test
    fun requestStartsWaiting() {
        val manager = manager()

        val result = manager.requestUnlock(
            sessionId = 1L,
            delay = UnlockDelay.MINUTES_30
        )

        assertEquals(
            UnlockRequestState.WAITING,
            result.state
        )

        assertEquals(
            1_800_000L,
            result.remainingMillis
        )
    }

    @Test
    fun requestBecomesReadyAfterDelay() {
        val manager = manager()

        manager.requestUnlock(
            1L,
            UnlockDelay.HOUR_1
        )

        now += 3_600_000L

        val result = manager.getStatus(1L)!!

        assertEquals(
            UnlockRequestState.READY,
            result.state
        )
    }

    @Test
    fun confirmationBeforeDeadlineIsRejected() {
        val manager = manager()

        val request = manager.requestUnlock(
            1L,
            UnlockDelay.HOURS_2
        )

        assertFalse(
            manager.confirm(
                1L,
                request.requestId
            )
        )

        assertEquals(
            UnlockRequestState.WAITING,
            manager.getStatus(1L)?.state
        )
    }

    @Test
    fun confirmationAfterDeadlineSucceeds() {
        val manager = manager()

        val request = manager.requestUnlock(
            1L,
            UnlockDelay.MINUTES_30
        )

        now += 1_800_000L

        assertTrue(
            manager.confirm(
                1L,
                request.requestId
            )
        )

        assertEquals(
            UnlockRequestState.CONFIRMED,
            manager.getStatus(1L)?.state
        )
    }

    @Test
    fun duplicateRequestDoesNotResetDeadline() {
        val manager = manager()

        val first = manager.requestUnlock(
            1L,
            UnlockDelay.HOUR_1
        )

        now += 600_000L

        val duplicate = manager.requestUnlock(
            1L,
            UnlockDelay.HOURS_4
        )

        assertEquals(
            first.requestId,
            duplicate.requestId
        )

        assertEquals(
            UnlockDelay.HOUR_1,
            duplicate.delay
        )

        assertEquals(
            3_000_000L,
            duplicate.remainingMillis
        )
    }

    @Test
    fun cancellationRequiresNewWaitingPeriod() {
        val manager = manager()

        val first = manager.requestUnlock(
            1L,
            UnlockDelay.MINUTES_30
        )

        assertTrue(
            manager.cancel(
                1L,
                first.requestId
            )
        )

        val second = manager.requestUnlock(
            1L,
            UnlockDelay.MINUTES_30
        )

        assertNotEquals(
            first.requestId,
            second.requestId
        )

        assertEquals(
            UnlockRequestState.WAITING,
            second.state
        )
    }

    @Test
    fun oldSessionCannotConfirmNewRequest() {
        val manager = manager()

        val old = manager.requestUnlock(
            1L,
            UnlockDelay.MINUTES_30
        )

        assertTrue(
            manager.cancel(
                1L,
                old.requestId
            )
        )

        val current = manager.requestUnlock(
            2L,
            UnlockDelay.MINUTES_30
        )

        now += 1_800_000L

        assertFalse(
            manager.confirm(
                1L,
                old.requestId
            )
        )

        assertEquals(
            UnlockRequestState.READY,
            manager.getStatus(2L)?.state
        )

        assertTrue(
            manager.confirm(
                2L,
                current.requestId
            )
        )
    }

    @Test
    fun cannotCancelConfirmedRequest() {
        val manager = manager()

        val request = manager.requestUnlock(
            1L,
            UnlockDelay.MINUTES_30
        )

        now += 1_800_000L

        assertTrue(
            manager.confirm(
                1L,
                request.requestId
            )
        )

        assertFalse(
            manager.cancel(
                1L,
                request.requestId
            )
        )
    }
}
