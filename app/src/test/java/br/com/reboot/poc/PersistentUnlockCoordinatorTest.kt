package br.com.reboot.poc

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PersistentUnlockCoordinatorTest {

    private class FakeRepository :
        PersistentUnlockRepository {

        var stored: StoredUnlockRequest? = null

        override fun save(request: StoredUnlockRequest) {
            stored = request
        }

        override fun load(): StoredUnlockRequest? =
            stored

        override fun clear() {
            stored = null
        }
    }

    private val sessionA = UUID.randomUUID().toString()
    private val sessionB = UUID.randomUUID().toString()

    private var now = 100_000L
    private var boot = 3

    private val repository = FakeRepository()

    private fun coordinator() =
        PersistentUnlockCoordinator(
            repository = repository,
            elapsedRealtimeMillis = { now },
            bootCount = { boot }
        )

    @Test
    fun savesNewUnlockRequest() {
        val manager = coordinator()

        val status = manager.requestUnlock(
            sessionA,
            UnlockDelay.MINUTES_30
        )

        assertNotNull(repository.stored)
        assertEquals(
            UnlockRequestState.WAITING,
            status.state
        )
        assertEquals(
            1_800_000L,
            status.remainingMillis
        )
    }

    @Test
    fun restoresRequestAfterCoordinatorRecreation() {
        val first = coordinator()

        val original = first.requestUnlock(
            sessionA,
            UnlockDelay.HOUR_1
        )

        now += 600_000L

        // Simula recriação do gerenciador.
        val restored = coordinator().getStatus(
            sessionA
        )!!

        assertEquals(
            original.requestId,
            restored.requestId
        )
        assertEquals(
            3_000_000L,
            restored.remainingMillis
        )
    }

    @Test
    fun duplicateRequestPreservesOriginalDeadline() {
        val manager = coordinator()

        val original = manager.requestUnlock(
            sessionA,
            UnlockDelay.HOUR_1
        )

        now += 600_000L

        val duplicate = manager.requestUnlock(
            sessionA,
            UnlockDelay.HOURS_4
        )

        assertEquals(
            original.requestId,
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
    fun cannotConfirmBeforeDeadline() {
        val manager = coordinator()

        val request = manager.requestUnlock(
            sessionA,
            UnlockDelay.MINUTES_30
        )

        assertFalse(
            manager.canConfirm(
                sessionA,
                request.requestId
            )
        )

        now += 1_800_000L

        assertTrue(
            manager.canConfirm(
                sessionA,
                request.requestId
            )
        )

        // Estar pronto não apaga o pedido
        // nem desliga a VPN.
        assertNotNull(repository.stored)
    }


    @Test
    fun cannotReplaceRequestFromAnotherSession() {
        val manager = coordinator()

        val original = manager.requestUnlock(
            sessionA,
            UnlockDelay.MINUTES_30
        )

        try {
            manager.requestUnlock(
                sessionB,
                UnlockDelay.HOURS_4
            )
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // A troca silenciosa deve ser impedida.
        }

        val restored = manager.getStatus(sessionA)

        assertNotNull(restored)
        assertEquals(
            original.requestId,
            restored!!.requestId
        )

        assertNull(manager.getStatus(sessionB))
    }


    @Test
    fun rebootInvalidatesPendingRequest() {
        val manager = coordinator()

        val request = manager.requestUnlock(
            sessionA,
            UnlockDelay.HOURS_2
        )

        // Simula reinicialização do aparelho.
        boot += 1
        now = 1_000L

        assertNull(manager.getStatus(sessionA))
        assertNull(repository.stored)

        assertFalse(
            manager.canConfirm(
                sessionA,
                request.requestId
            )
        )
    }

    @Test
    fun cancellationRemovesPendingRequest() {
        val manager = coordinator()

        val request = manager.requestUnlock(
            sessionA,
            UnlockDelay.MINUTES_30
        )

        assertTrue(
            manager.cancel(
                sessionA,
                request.requestId
            )
        )

        assertNull(manager.getStatus(sessionA))
        assertNull(repository.stored)
    }

    @Test
    fun otherSessionCannotCancelPendingRequest() {
        val manager = coordinator()

        val request = manager.requestUnlock(
            sessionA,
            UnlockDelay.HOUR_1
        )

        assertFalse(
            manager.cancel(
                sessionB,
                request.requestId
            )
        )

        assertNotNull(manager.getStatus(sessionA))
    }

}
