package br.com.reboot.poc

import br.com.reboot.poc.domain.unlock.UnlockDeadline
import br.com.reboot.poc.domain.unlock.UnlockDelay
import org.junit.Assert.*
import org.junit.Test

class UnlockDeadlineTest {

    @Test
    fun supportsFourDelays() {
        assertEquals(
            4,
            UnlockDelay.entries.size
        )
        assertEquals(
            30 * 60_000L,
            UnlockDelay.MINUTES_30.durationMillis
        )
        assertEquals(
            240 * 60_000L,
            UnlockDelay.HOURS_4.durationMillis
        )
    }

    @Test
    fun startsWhenUnlockIsRequested() {
        val request = UnlockDeadline.request(
            UnlockDelay.HOURS_2,
            100_000L
        )

        assertEquals(
            100_000L + 7_200_000L,
            request.availableAtMillis
        )
    }

    @Test
    fun cannotUnlockBeforeDeadline() {
        val request = UnlockDeadline.request(
            UnlockDelay.MINUTES_30,
            0L
        )

        assertFalse(request.isReady(1_799_999L))
        assertEquals(
            1L,
            request.remainingMillis(1_799_999L)
        )
    }

    @Test
    fun unlockBecomesAvailableAtDeadline() {
        val request = UnlockDeadline.request(
            UnlockDelay.MINUTES_30,
            0L
        )

        assertTrue(request.isReady(1_800_000L))
        assertEquals(
            0L,
            request.remainingMillis(2_000_000L)
        )
    }

    @Test
    fun rejectsInvalidRequestTime() {
        try {
            UnlockDeadline.request(
                UnlockDelay.HOUR_1,
                -1L
            )
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Esperado.
        }
    }
}