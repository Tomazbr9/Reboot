
package br.com.reboot.poc

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.application.protection.ProtectionSessionRecorder
import br.com.reboot.poc.application.protection.ProtectionStopCleanupResult
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtectionStopCleanupTest {

    private val sessionKey = UUID.randomUUID().toString()
    private val otherSessionKey = UUID.randomUUID().toString()

    private class FakeSessions :
        ProtectionSessionRecordRepository {

        var record: ProtectionSessionRecord? = null

        override fun load(): ProtectionSessionRecord? =
            record

        override fun save(record: ProtectionSessionRecord) {
            this.record = record
        }

        override fun clear() {
            record = null
        }
    }

    private class FakeUnlocks :
        PersistentUnlockRepository {

        var request: StoredUnlockRequest? = null
        var failNextClear = false

        override fun load(): StoredUnlockRequest? =
            request

        override fun save(request: StoredUnlockRequest) {
            this.request = request
        }

        override fun clear() {
            if (failNextClear) {
                failNextClear = false
                throw IllegalStateException(
                    "Simulated persistence failure"
                )
            }

            request = null
        }
    }

    private val sessions = FakeSessions()
    private val unlocks = FakeUnlocks()

    private val recorder = ProtectionSessionRecorder(
        sessions = sessions,
        unlocks = unlocks
    )

    private fun session(
        key: String = sessionKey
    ) = ProtectionSessionRecord(
        sessionKey = key,
        bootCount = 3,
        activatedAtElapsedMillis = 100_000L
    )

    private fun unlock(
        key: String = sessionKey
    ) = StoredUnlockRequest.create(
        protectionSessionKey = key,
        delay = UnlockDelay.MINUTES_30,
        nowElapsedMillis = 100_000L,
        bootCount = 3
    )

    @Test
    fun cleansSessionAndUnlock() {
        sessions.record = session()
        unlocks.request = unlock()

        val result = recorder.onStoppedDetailed(sessionKey)

        assertEquals(
            ProtectionStopCleanupResult.CLEANED,
            result
        )

        assertNull(sessions.record)
        assertNull(unlocks.request)
    }

    @Test
    fun repeatedCleanupIsSafe() {
        sessions.record = session()

        assertEquals(
            ProtectionStopCleanupResult.CLEANED,
            recorder.onStoppedDetailed(sessionKey)
        )

        assertEquals(
            ProtectionStopCleanupResult.ALREADY_CLEAN,
            recorder.onStoppedDetailed(sessionKey)
        )
    }

    @Test
    fun orphanUnlockCanBeCleaned() {
        unlocks.request = unlock()

        assertEquals(
            ProtectionStopCleanupResult.CLEANED,
            recorder.onStoppedDetailed(sessionKey)
        )

        assertNull(unlocks.request)
    }

    @Test
    fun doesNotDeleteAnotherSession() {
        sessions.record = session(otherSessionKey)
        unlocks.request = unlock(otherSessionKey)

        assertEquals(
            ProtectionStopCleanupResult.SESSION_MISMATCH,
            recorder.onStoppedDetailed(sessionKey)
        )

        assertNotNull(sessions.record)
        assertNotNull(unlocks.request)
    }

    @Test
    fun failureDoesNotSilentlyReportSuccess() {
        sessions.record = session()
        unlocks.request = unlock()
        unlocks.failNextClear = true

        try {
            recorder.onStoppedDetailed(sessionKey)
            fail("Expected persistence failure")
        } catch (_: IllegalStateException) {
            // Falha identificada corretamente.
        }

        assertNotNull(sessions.record)
        assertNotNull(unlocks.request)

        // Uma segunda tentativa deve conseguir
        // finalizar a limpeza.
        assertEquals(
            ProtectionStopCleanupResult.CLEANED,
            recorder.onStoppedDetailed(sessionKey)
        )

        assertNull(sessions.record)
        assertNull(unlocks.request)
    }
}
