
package br.com.reboot.poc

import br.com.reboot.poc.application.ports.PendingStopCleanupRepository
import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.application.protection.PendingStopCleanupRecovery
import br.com.reboot.poc.application.protection.PendingStopRecoveryResult
import br.com.reboot.poc.application.protection.VpnOperationalSnapshot
import br.com.reboot.poc.domain.protection.PendingStopCleanup
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PendingStopCleanupRecoveryTest {

    private val keyA = UUID.randomUUID().toString()
    private val keyB = UUID.randomUUID().toString()

    private val pending = object : PendingStopCleanupRepository {
        var value: PendingStopCleanup? = null

        override fun load() = value

        override fun save(value: PendingStopCleanup) {
            this.value = value
        }

        override fun clear() {
            value = null
        }
    }

    private val sessions =
        object : ProtectionSessionRecordRepository {
            var value: ProtectionSessionRecord? = null

            override fun load() = value

            override fun save(record: ProtectionSessionRecord) {
                value = record
            }

            override fun clear() {
                value = null
            }
        }

    private val unlocks =
        object : PersistentUnlockRepository {
            var value: StoredUnlockRequest? = null

            override fun load() = value

            override fun save(request: StoredUnlockRequest) {
                value = request
            }

            override fun clear() {
                value = null
            }
        }

    private val recovery = PendingStopCleanupRecovery(
        pending = pending,
        sessions = sessions,
        unlocks = unlocks
    )

    private fun prepare(key: String = keyA) {
        pending.save(PendingStopCleanup(key))

        sessions.save(
            ProtectionSessionRecord(
                sessionKey = key,
                bootCount = 3,
                activatedAtElapsedMillis = 100_000L
            )
        )

        unlocks.save(
            StoredUnlockRequest.create(
                protectionSessionKey = key,
                delay = UnlockDelay.MINUTES_30,
                nowElapsedMillis = 100_000L,
                bootCount = 3
            )
        )
    }

    @Test
    fun unknownSnapshotMustPreserveEverything() {
        prepare()

        assertEquals(
            PendingStopRecoveryResult.WAITING_FOR_VERIFICATION,
            recovery.recover(VpnOperationalSnapshot.Unknown)
        )

        assertNotNull(pending.load())
        assertNotNull(sessions.load())
        assertNotNull(unlocks.load())
    }

    @Test
    fun activeVpnMustPreserveEverything() {
        prepare()

        assertEquals(
            PendingStopRecoveryResult.VPN_STILL_ACTIVE,
            recovery.recover(VpnOperationalSnapshot.Active(keyA))
        )

        assertNotNull(pending.load())
        assertNotNull(sessions.load())
    }

    @Test
    fun inactiveVpnAllowsCleanup() {
        prepare()

        assertEquals(
            PendingStopRecoveryResult.CLEANED,
            recovery.recover(VpnOperationalSnapshot.Inactive)
        )

        assertNull(pending.load())
        assertNull(sessions.load())
        assertNull(unlocks.load())
    }

    @Test
    fun differentSessionMustNotBeDeleted() {
        prepare()

        sessions.save(
            ProtectionSessionRecord(
                sessionKey = keyB,
                bootCount = 3,
                activatedAtElapsedMillis = 200_000L
            )
        )

        assertEquals(
            PendingStopRecoveryResult.SESSION_MISMATCH,
            recovery.recover(VpnOperationalSnapshot.Inactive)
        )

        assertNotNull(pending.load())
        assertEquals(keyB, sessions.load()?.sessionKey)
    }

    @Test
    fun noMarkerMeansNoRecovery() {
        assertEquals(
            PendingStopRecoveryResult.NO_PENDING_CLEANUP,
            recovery.recover(VpnOperationalSnapshot.Inactive)
        )
    }

    @Test
    fun repeatedRecoveryIsIdempotent() {
        prepare()

        assertEquals(
            PendingStopRecoveryResult.CLEANED,
            recovery.recover(VpnOperationalSnapshot.Inactive)
        )

        assertEquals(
            PendingStopRecoveryResult.NO_PENDING_CLEANUP,
            recovery.recover(VpnOperationalSnapshot.Inactive)
        )
    }
}
