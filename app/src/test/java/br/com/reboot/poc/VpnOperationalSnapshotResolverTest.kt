
package br.com.reboot.poc

import br.com.reboot.poc.application.protection.VpnOperationalSnapshot
import br.com.reboot.poc.infrastructure.vpn.VpnOperationalSnapshotResolver
import br.com.reboot.poc.infrastructure.vpn.VpnSessionGuard
import br.com.reboot.poc.infrastructure.vpn.VpnSessionResources
import org.junit.Assert.*
import org.junit.Test

class VpnOperationalSnapshotResolverTest {

    @Test
    fun noSessionReturnsUnknown() {
        val guard = VpnSessionGuard()
        val resolver = VpnOperationalSnapshotResolver(guard)

        assertEquals(
            VpnOperationalSnapshot.Unknown,
            resolver.resolve(null)
        )
    }

    @Test
    fun startingSessionReturnsUnknown() {
        val guard = VpnSessionGuard()
        val id = guard.beginStart()!!
        val session = VpnSessionResources(id)

        try {
            val snapshot =
                VpnOperationalSnapshotResolver(guard)
                    .resolve(session)

            assertEquals(
                VpnOperationalSnapshot.Unknown,
                snapshot
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun activeSessionReturnsMatchingUuid() {
        val guard = VpnSessionGuard()
        val id = guard.beginStart()!!
        val session = VpnSessionResources(id)

        try {
            assertTrue(guard.markActive(id))

            val snapshot =
                VpnOperationalSnapshotResolver(guard)
                    .resolve(session)

            assertTrue(
                snapshot is VpnOperationalSnapshot.Active
            )

            assertEquals(
                session.sessionKey,
                (snapshot as VpnOperationalSnapshot.Active)
                    .sessionKey
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun stoppingSessionReturnsUnknown() {
        val guard = VpnSessionGuard()
        val id = guard.beginStart()!!
        val session = VpnSessionResources(id)

        try {
            assertTrue(guard.markActive(id))
            assertEquals(id, guard.beginStop())

            assertEquals(
                VpnOperationalSnapshot.Unknown,
                VpnOperationalSnapshotResolver(guard)
                    .resolve(session)
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun closedSessionIsNotReportedAsActive() {
        val guard = VpnSessionGuard()
        val id = guard.beginStart()!!
        val session = VpnSessionResources(id)

        assertTrue(guard.markActive(id))
        session.close()

        assertEquals(
            VpnOperationalSnapshot.Unknown,
            VpnOperationalSnapshotResolver(guard)
                .resolve(session)
        )
    }

    @Test
    fun staleSessionIsNotReportedAsActive() {
        val guard = VpnSessionGuard()

        val oldId = guard.beginStart()!!
        val oldSession = VpnSessionResources(oldId)

        val newSession: VpnSessionResources

        try {
            assertTrue(guard.markActive(oldId))
            assertEquals(oldId, guard.beginStop())
            guard.finishStop(oldId)

            val newId = guard.beginStart()!!
            newSession = VpnSessionResources(newId)

            try {
                assertTrue(guard.markActive(newId))

                val resolver =
                    VpnOperationalSnapshotResolver(guard)

                assertEquals(
                    VpnOperationalSnapshot.Unknown,
                    resolver.resolve(oldSession)
                )

                val current = resolver.resolve(newSession)

                assertEquals(
                    newSession.sessionKey,
                    (current as VpnOperationalSnapshot.Active)
                        .sessionKey
                )
            } finally {
                newSession.close()
            }
        } finally {
            oldSession.close()
        }
    }
}
