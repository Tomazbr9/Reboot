package br.com.reboot.poc

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.application.protection.ProtectionRecoveryCoordinator
import br.com.reboot.poc.application.protection.ProtectionRecoveryResult
import br.com.reboot.poc.application.protection.VpnOperationalSnapshot
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtectionRecoveryCoordinatorTest {

    private class FakeSessions :
        ProtectionSessionRecordRepository {

        var stored: ProtectionSessionRecord? = null

        override fun save(record: ProtectionSessionRecord) {
            stored = record
        }

        override fun load(): ProtectionSessionRecord? =
            stored

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

        override fun load(): StoredUnlockRequest? =
            stored

        override fun clear() {
            stored = null
        }
    }

    private val sessionA = UUID.randomUUID().toString()
    private val sessionB = UUID.randomUUID().toString()

    private val sessions = FakeSessions()
    private val unlocks = FakeUnlocks()

    private val coordinator = ProtectionRecoveryCoordinator(
        sessions = sessions,
        unlocks = unlocks
    )

    private fun saveSession() {
        sessions.save(
            ProtectionSessionRecord(
                sessionKey = sessionA,
                bootCount = 3,
                activatedAtElapsedMillis = 100_000L
            )
        )
    }

    private fun saveUnlock(key: String = sessionA) {
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
    fun unknownStatePreservesAllRecords() {
        saveSession()
        saveUnlock()

        val result = coordinator.recover(
            VpnOperationalSnapshot.Unknown,
            3
        )

        assertEquals(
            ProtectionRecoveryResult
                .WAITING_FOR_VERIFICATION,
            result
        )

        assertNotNull(sessions.load())
        assertNotNull(unlocks.load())
    }

    @Test
    fun activeMatchingSessionPreservesData() {
        saveSession()
        saveUnlock()

        val result = coordinator.recover(
            VpnOperationalSnapshot.Active(sessionA),
            3
        )

        assertEquals(
            ProtectionRecoveryResult
                .ACTIVE_SESSION_MATCHED,
            result
        )

        assertNotNull(sessions.load())
        assertNotNull(unlocks.load())
    }

    @Test
    fun differentActiveSessionClearsOldData() {
        saveSession()
        saveUnlock()

        val result = coordinator.recover(
            VpnOperationalSnapshot.Active(sessionB),
            3
        )

        assertEquals(
            ProtectionRecoveryResult
                .STALE_SESSION_CLEARED,
            result
        )

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun inactiveSessionClearsOldData() {
        saveSession()
        saveUnlock()

        val result = coordinator.recover(
            VpnOperationalSnapshot.Inactive,
            3
        )

        assertEquals(
            ProtectionRecoveryResult
                .STALE_SESSION_CLEARED,
            result
        )

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun orphanUnlockIsPreservedForMatchingActiveSession() {
        saveUnlock()

        val result = coordinator.recover(
            VpnOperationalSnapshot.Active(sessionA),
            3
        )

        assertEquals(
            ProtectionRecoveryResult
                .ORPHAN_UNLOCK_PRESERVED,
            result
        )

        assertNotNull(unlocks.load())
    }

    @Test
    fun orphanUnlockIsClearedForDifferentSession() {
        saveUnlock()

        val result = coordinator.recover(
            VpnOperationalSnapshot.Active(sessionB),
            3
        )

        assertEquals(
            ProtectionRecoveryResult
                .ORPHAN_UNLOCK_CLEARED,
            result
        )

        assertNull(unlocks.load())
    }

    @Test
    fun emptyStorageRequiresNoRecovery() {
        val result = coordinator.recover(
            VpnOperationalSnapshot.Inactive,
            3
        )

        assertEquals(
            ProtectionRecoveryResult.NO_SAVED_DATA,
            result
        )
    }

    @Test
    fun previousBootSessionIsClearedAfterVerification() {
        saveSession()
        saveUnlock()

        val result = coordinator.recover(
            VpnOperationalSnapshot.Inactive,
            4
        )

        assertEquals(
            ProtectionRecoveryResult
                .STALE_SESSION_CLEARED,
            result
        )

        assertNull(sessions.load())
        assertNull(unlocks.load())
    }
}
