package br.com.reboot.poc

import org.junit.Assert.*
import org.junit.Test

class VpnSessionGuardTest {

    @Test
    fun startsInactive() {
        val guard = VpnSessionGuard()

        assertEquals(
            VpnState.INACTIVE,
            guard.currentState()
        )
    }

    @Test
    fun rejectsDuplicateStart() {
        val guard = VpnSessionGuard()

        assertNotNull(guard.beginStart())
        assertNull(guard.beginStart())
    }

    @Test
    fun activatesReservedSession() {
        val guard = VpnSessionGuard()
        val id = guard.beginStart()!!

        assertTrue(guard.markActive(id))
        assertTrue(guard.isActive(id))
    }

    @Test
    fun rejectsStaleSession() {
        val guard = VpnSessionGuard()

        val oldId = guard.beginStart()!!
        guard.beginStop()
        guard.finishStop(oldId)

        val newId = guard.beginStart()!!

        assertNotEquals(oldId, newId)
        assertFalse(guard.markActive(oldId))
        assertFalse(guard.isActive(oldId))
        assertTrue(guard.markActive(newId))
    }

    @Test
    fun stopsActiveSession() {
        val guard = VpnSessionGuard()

        val id = guard.beginStart()!!
        guard.markActive(id)

        assertEquals(id, guard.beginStop())
        assertFalse(guard.isActive(id))

        guard.finishStop(id)

        assertEquals(
            VpnState.INACTIVE,
            guard.currentState()
        )
    }

    @Test
    fun rejectsStartWhileStopping() {
        val guard = VpnSessionGuard()

        val id = guard.beginStart()!!
        guard.markActive(id)
        guard.beginStop()

        assertNull(guard.beginStart())

        guard.finishStop(id)

        assertNotNull(guard.beginStart())
    }


    @Test
    fun stopDuringStartingPreventsActivation() {
        val guard = VpnSessionGuard()

        val id = guard.beginStart()!!

        assertEquals(id, guard.beginStop())
        assertFalse(guard.markActive(id))

        guard.finishStop(id)

        assertEquals(
            VpnState.INACTIVE,
            guard.currentState()
        )
    }

    @Test
    fun oldSessionCannotFinishNewSession() {
        val guard = VpnSessionGuard()

        val first = guard.beginStart()!!
        guard.beginStop()
        guard.finishStop(first)

        val second = guard.beginStart()!!
        assertTrue(guard.markActive(second))

        // Callback tardio da sessão anterior.
        guard.finishStop(first)

        assertTrue(guard.isActive(second))
        assertEquals(
            VpnState.ACTIVE,
            guard.currentState()
        )
    }

    @Test
    fun repeatedStopDoesNotCreateSecondStop() {
        val guard = VpnSessionGuard()

        val id = guard.beginStart()!!
        guard.markActive(id)

        assertEquals(id, guard.beginStop())
        assertNull(guard.beginStop())

        guard.finishStop(id)

        assertEquals(
            VpnState.INACTIVE,
            guard.currentState()
        )
    }


    @Test
    fun failedStartCanBeStopped() {
        val guard = VpnSessionGuard()
        val id = guard.beginStart()!!

        // Simula falha antes de ACTIVE.
        assertTrue(guard.ensureStopping(id))
        guard.finishStop(id)

        assertEquals(
            VpnState.INACTIVE,
            guard.currentState()
        )

        assertNotNull(guard.beginStart())
    }

    @Test
    fun stoppingCanBeRequestedRepeatedly() {
        val guard = VpnSessionGuard()
        val id = guard.beginStart()!!

        assertTrue(guard.markActive(id))
        assertTrue(guard.ensureStopping(id))
        assertTrue(guard.ensureStopping(id))

        guard.finishStop(id)

        assertEquals(
            VpnState.INACTIVE,
            guard.currentState()
        )
    }

    @Test
    fun staleSessionCannotStopNewSession() {
        val guard = VpnSessionGuard()

        val oldId = guard.beginStart()!!
        assertTrue(guard.ensureStopping(oldId))
        guard.finishStop(oldId)

        val newId = guard.beginStart()!!
        assertTrue(guard.markActive(newId))

        assertFalse(guard.ensureStopping(oldId))
        assertTrue(guard.isActive(newId))
    }


}
