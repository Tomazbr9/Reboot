
package br.com.reboot.poc

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.application.protection.ProtectionSessionRecorder
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtectionSessionRecorderTest {

    private class FakeSessions :
        ProtectionSessionRecordRepository {

        var stored: ProtectionSessionRecord? = null

        override fun save(record: ProtectionSessionRecord) {
            stored = record
        }

        override fun load() = stored

        override fun clear() {
            stored = null
        }
    }

    private class FakeUnlocks :
        PersistentUnlockRepository {

        var stored: StoredUnlockRequest? = null

        override fun save(request: StoredUnlockRequest) {
            stored = request
        }

        override fun load() = stored

        override fun clear() {
            stored = null
        }
    }

    private val sessions = FakeSessions()
    private val unlocks = FakeUnlocks()

    private val keyA = UUID.randomUUID().toString()
    private val keyB = UUID.randomUUID().toString()

    private val recorder = ProtectionSessionRecorder(
        sessions,
        unlocks
    )

    private fun record(key: String) =
        ProtectionSessionRecord(
            sessionKey = key,
            bootCount = 3,
            activatedAtElapsedMillis = 100_000L
        )

    private fun unlock(key: String) =
        StoredUnlockRequest.create(
            protectionSessionKey = key,
            delay = UnlockDelay.HOUR_1,
            nowElapsedMillis = 100_000L,
            bootCount = 3
        )

    @Test
    fun activationPersistsSession() {
        recorder.onActivated(record(keyA))

        assertEquals(
            keyA,
            sessions.load()?.sessionKey
        )
    }

    @Test
    fun normalStopClearsMatchingRecord() {
        recorder.onActivated(record(keyA))
        unlocks.save(unlock(keyA))

        assertTrue(recorder.onStopped(keyA))

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun oldStopCannotClearNewSession() {
        recorder.onActivated(record(keyA))
        recorder.onActivated(record(keyB))

        assertFalse(recorder.onStopped(keyA))

        assertEquals(
            keyB,
            sessions.load()?.sessionKey
        )
    }

    @Test
    fun newActivationInvalidatesOldUnlock() {
        recorder.onActivated(record(keyA))
        unlocks.save(unlock(keyA))

        recorder.onActivated(record(keyB))

        assertNull(unlocks.load())
        assertEquals(
            keyB,
            sessions.load()?.sessionKey
        )
    }

    @Test
    fun stopDoesNotClearOtherSessionsUnlock() {
        recorder.onActivated(record(keyA))
        unlocks.save(unlock(keyB))

        assertTrue(recorder.onStopped(keyA))

        assertNull(sessions.load())
        assertEquals(
            keyB,
            unlocks.load()?.protectionSessionKey
        )
    }
}
