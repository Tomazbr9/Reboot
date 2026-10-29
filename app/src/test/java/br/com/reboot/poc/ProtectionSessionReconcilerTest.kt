package br.com.reboot.poc

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtectionSessionReconcilerTest {

    private class FakeSessions :
        ProtectionSessionRecordRepository {

        var record: ProtectionSessionRecord? = null

        override fun save(record: ProtectionSessionRecord) {
            this.record = record
        }

        override fun load() = record

        override fun clear() {
            record = null
        }
    }

    private class FakeUnlocks :
        PersistentUnlockRepository {

        var request: StoredUnlockRequest? = null

        override fun save(request: StoredUnlockRequest) {
            this.request = request
        }

        override fun load() = request

        override fun clear() {
            request = null
        }
    }

    private val sessionA = UUID.randomUUID().toString()
    private val sessionB = UUID.randomUUID().toString()

    private val sessions = FakeSessions()
    private val unlocks = FakeUnlocks()

    private fun reconciler() =
        ProtectionSessionReconciler(
            sessions,
            unlocks
        )

    private fun storeSession() {
        sessions.save(
            ProtectionSessionRecord(
                sessionKey = sessionA,
                bootCount = 3,
                activatedAtElapsedMillis = 100_000L
            )
        )
    }

    private fun storeUnlock(key: String = sessionA) {
        unlocks.save(
            StoredUnlockRequest.create(
                protectionSessionKey = key,
                delay = UnlockDelay.HOUR_1,
                nowElapsedMillis = 100_000L,
                bootCount = 3
            )
        )
    }

    @Test
    fun matchingActiveSessionPreservesRecords() {
        storeSession()
        storeUnlock()

        assertEquals(
            SessionReconciliationResult
                .MATCHED_ACTIVE_SESSION,
            reconciler().reconcile(sessionA, 3)
        )

        assertNotNull(sessions.load())
        assertNotNull(unlocks.load())
    }

    @Test
    fun differentSessionInvalidatesOldUnlock() {
        storeSession()
        storeUnlock()

        assertEquals(
            SessionReconciliationResult
                .STALE_RECORD_CLEARED,
            reconciler().reconcile(sessionB, 3)
        )

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun confirmedInactiveClearsOldRecord() {
        storeSession()
        storeUnlock()

        reconciler().reconcile(null, 3)

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun deviceRebootInvalidatesOldRecord() {
        storeSession()
        storeUnlock()

        reconciler().reconcile(sessionA, 4)

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun doesNotDeleteOtherSessionsUnlock() {
        storeSession()
        storeUnlock(sessionB)

        reconciler().reconcile(null, 3)

        assertNull(sessions.load())
        assertNotNull(unlocks.load())
    }

    @Test
    fun returnsNoRecordWhenStorageIsEmpty() {
        assertEquals(
            SessionReconciliationResult.NO_RECORD,
            reconciler().reconcile(sessionA, 3)
        )
    }
}
