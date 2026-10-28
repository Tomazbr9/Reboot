package br.com.reboot.poc

import org.junit.Assert.*
import org.junit.Test

class VpnSessionIdentityTest {

    @Test
    fun createsUniqueKeysForSameLocalId() {
        val first = VpnSessionIdentity.create(1L)
        val second = VpnSessionIdentity.create(1L)

        assertEquals(1L, first.localId)
        assertEquals(1L, second.localId)

        assertNotEquals(
            first.sessionKey,
            second.sessionKey
        )
    }

    @Test
    fun resourcesUseOwnSessionIdentity() {
        val first = VpnSessionResources(1L)
        val second = VpnSessionResources(1L)

        try {
            assertNotEquals(
                first.sessionKey,
                second.sessionKey
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun rejectsMismatchedSessionIdentity() {
        val identity = VpnSessionIdentity.create(2L)

        try {
            VpnSessionResources(
                sessionId = 1L,
                identity = identity
            )

            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Esperado.
        }
    }
}
