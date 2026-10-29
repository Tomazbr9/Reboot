
package br.com.reboot.poc

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.application.protection.OrphanUnlockRecovery
import br.com.reboot.poc.application.protection.OrphanUnlockRecoveryResult
import br.com.reboot.poc.application.protection.VpnOperationalSnapshot
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OrphanUnlockRecoveryTest {

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

    private val sessionA = UUID.randomUUID().toString()
    private val sessionB = UUID.randomUUID().toString()

    private val sessions = FakeSessions()
    private val unlocks = FakeUnlocks()

    private val recovery = OrphanUnlockRecovery(
        sessions,
        unlocks
    )

    private fun saveUnlock() {
        unlocks.save(
            StoredUnlockRequest.create(
                protectionSessionKey = sessionA,
                delay = UnlockDelay.HOUR_1,
                nowElapsedMillis = 100_000L,
                bootCount = 3
            )
        )
    }

    @Test
    fun noRequestRequiresNoRecovery() {
        assertEquals(
            OrphanUnlockRecoveryResult.NO_UNLOCK_REQUEST,
            recovery.recover(
                VpnOperationalSnapshot.Unknown,
                3
            )
        )
    }

    @Test
    fun existingSessionRecordIsPreserved() {
        saveUnlock()

        sessions.save(
            ProtectionSessionRecord(
                sessionKey = sessionA,
                bootCount = 3,
                activatedAtElapsedMillis = 100_000L
            )
        )

        assertEquals(
            OrphanUnlockRecoveryResult.SESSION_RECORD_PRESENT,
            recovery.recover(
                VpnOperationalSnapshot.Inactive,
                3
            )
        )

        assertNotNull(unlocks.load())
    }

    @Test
    fun unknownStateNeverClearsRequest() {
        saveUnlock()

        assertEquals(
            OrphanUnlockRecoveryResult.WAITING_FOR_VERIFICATION,
            recovery.recover(
                VpnOperationalSnapshot.Unknown,
                3
            )
        )

        assertNotNull(unlocks.load())
    }

    @Test
    fun matchingActiveSessionPreservesRequest() {
        saveUnlock()

        assertEquals(
            OrphanUnlockRecoveryResult.ACTIVE_SESSION_MATCHES,
            recovery.recover(
                VpnOperationalSnapshot.Active(sessionA),
                3
            )
        )

        assertNotNull(unlocks.load())
    }

    @Test
    fun differentActiveSessionClearsOrphan() {
        saveUnlock()

        assertEquals(
            OrphanUnlockRecoveryResult.ORPHAN_UNLOCK_CLEARED,
            recovery.recover(
                VpnOperationalSnapshot.Active(sessionB),
                3
            )
        )

        assertNull(unlocks.load())
    }

    @Test
    fun confirmedInactiveClearsOrphan() {
        saveUnlock()

        assertEquals(
            OrphanUnlockRecoveryResult.ORPHAN_UNLOCK_CLEARED,
            recovery.recover(
                VpnOperationalSnapshot.Inactive,
                3
            )
        )

        assertNull(unlocks.load())
    }

    @Test
    fun previousBootRequestCannotMatchNewBootSession() {
        saveUnlock()

        assertEquals(
            OrphanUnlockRecoveryResult.ORPHAN_UNLOCK_CLEARED,
            recovery.recover(
                VpnOperationalSnapshot.Active(sessionA),
                4
            )
        )

        assertNull(unlocks.load())
    }
}
