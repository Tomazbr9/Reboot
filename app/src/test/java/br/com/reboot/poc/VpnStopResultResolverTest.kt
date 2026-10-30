
package br.com.reboot.poc

import br.com.reboot.poc.infrastructure.vpn.VpnStopResult
import br.com.reboot.poc.infrastructure.vpn.VpnStopResultResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnStopResultResolverTest {

    @Test
    fun successfulCleanupReturnsStopped() {
        val result = VpnStopResultResolver.resolve(
            resourceCleanupSucceeded = true,
            persistenceCleanupSucceeded = true,
            foregroundCleanupSucceeded = true
        )

        assertEquals(
            VpnStopResult.STOPPED,
            result
        )
    }

    @Test
    fun resourceFailureReturnsStoppedWithErrors() {
        val result = VpnStopResultResolver.resolve(
            resourceCleanupSucceeded = false,
            persistenceCleanupSucceeded = true,
            foregroundCleanupSucceeded = true
        )

        assertEquals(
            VpnStopResult.STOPPED_WITH_ERRORS,
            result
        )
    }

    @Test
    fun persistenceFailureReturnsStoppedWithErrors() {
        val result = VpnStopResultResolver.resolve(
            resourceCleanupSucceeded = true,
            persistenceCleanupSucceeded = false,
            foregroundCleanupSucceeded = true
        )

        assertEquals(
            VpnStopResult.STOPPED_WITH_ERRORS,
            result
        )
    }

    @Test
    fun foregroundFailureReturnsStoppedWithErrors() {
        val result = VpnStopResultResolver.resolve(
            resourceCleanupSucceeded = true,
            persistenceCleanupSucceeded = true,
            foregroundCleanupSucceeded = false
        )

        assertEquals(
            VpnStopResult.STOPPED_WITH_ERRORS,
            result
        )
    }

    @Test
    fun multipleFailuresReturnStoppedWithErrors() {
        val result = VpnStopResultResolver.resolve(
            resourceCleanupSucceeded = false,
            persistenceCleanupSucceeded = false,
            foregroundCleanupSucceeded = false
        )

        assertEquals(
            VpnStopResult.STOPPED_WITH_ERRORS,
            result
        )
    }
}
