
package br.com.reboot.poc

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.application.protection.ProtectionSessionReconciler
import br.com.reboot.poc.application.protection.SessionReconciliationResult
import br.com.reboot.poc.application.protection.VpnOperationalSnapshot
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
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

        override fun load(): ProtectionSessionRecord? {
            return record
        }

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

        override fun load(): StoredUnlockRequest? {
            return request
        }

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
            sessions = sessions,
            unlocks = unlocks
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

    private fun storeUnlock(
        key: String = sessionA
    ) {
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

        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Active(sessionA),
            currentBootCount = 3
        )

        assertEquals(
            SessionReconciliationResult.MATCHED_ACTIVE_SESSION,
            result
        )

        assertNotNull(sessions.load())
        assertNotNull(unlocks.load())
    }

    @Test
    fun differentSessionInvalidatesOldUnlock() {
        storeSession()
        storeUnlock()

        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Active(sessionB),
            currentBootCount = 3
        )

        assertEquals(
            SessionReconciliationResult.STALE_RECORD_CLEARED,
            result
        )

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun confirmedInactiveClearsOldRecord() {
        storeSession()
        storeUnlock()

        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Inactive,
            currentBootCount = 3
        )

        assertEquals(
            SessionReconciliationResult.STALE_RECORD_CLEARED,
            result
        )

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun deviceRebootInvalidatesOldRecord() {
        storeSession()
        storeUnlock()

        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Active(sessionA),
            currentBootCount = 4
        )

        assertEquals(
            SessionReconciliationResult.STALE_RECORD_CLEARED,
            result
        )

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun doesNotDeleteOtherSessionsUnlock() {
        storeSession()
        storeUnlock(sessionB)

        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Inactive,
            currentBootCount = 3
        )

        assertEquals(
            SessionReconciliationResult.STALE_RECORD_CLEARED,
            result
        )

        assertNull(sessions.load())
        assertNotNull(unlocks.load())

        assertEquals(
            sessionB,
            unlocks.load()?.protectionSessionKey
        )
    }

    @Test
    fun returnsNoRecordWhenStorageIsEmpty() {
        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Active(sessionA),
            currentBootCount = 3
        )

        assertEquals(
            SessionReconciliationResult.NO_RECORD,
            result
        )

        assertNull(sessions.load())
    }

    @Test
    fun unknownVpnStatePreservesRecords() {
        storeSession()
        storeUnlock()

        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Unknown,
            currentBootCount = 3
        )

        assertEquals(
            SessionReconciliationResult.WAITING_FOR_VERIFICATION,
            result
        )

        assertNotNull(sessions.load())
        assertNotNull(unlocks.load())
    }

    @Test
    fun unknownStateAfterBootChangeDoesNotClearRecords() {
        storeSession()
        storeUnlock()

        val result = reconciler().reconcile(
            VpnOperationalSnapshot.Unknown,
            currentBootCount = 4
        )

        assertEquals(
            SessionReconciliationResult.WAITING_FOR_VERIFICATION,
            result
        )

        assertNotNull(sessions.load())
        assertNotNull(unlocks.load())
    }
}
