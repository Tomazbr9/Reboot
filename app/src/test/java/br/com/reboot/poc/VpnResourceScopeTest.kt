
package br.com.reboot.poc

import br.com.reboot.poc.infrastructure.vpn.VpnResourceScope
import org.junit.Assert.*
import org.junit.Test

class VpnResourceScopeTest {

    private class TestResource(
        private val onClose: () -> Unit
    ) : AutoCloseable {
        override fun close() = onClose()
    }

    @Test
    fun closesResourcesInReverseOrder() {
        val scope = VpnResourceScope()
        val order = mutableListOf<String>()

        scope.register(TestResource { order.add("tunnel") })
        scope.register(TestResource { order.add("relay") })

        scope.close()

        assertEquals(
            listOf("relay", "tunnel"),
            order
        )
    }

    @Test
    fun closeIsIdempotent() {
        val scope = VpnResourceScope()
        var calls = 0

        scope.register(TestResource { calls++ })

        scope.close()
        scope.close()

        assertEquals(1, calls)
    }

    @Test
    fun closesAllResourcesEvenWhenOneFails() {
        val scope = VpnResourceScope()
        var firstClosed = false

        scope.register(
            TestResource { firstClosed = true }
        )

        scope.register(
            TestResource {
                throw IllegalStateException("test failure")
            }
        )

        try {
            scope.close()
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertTrue(firstClosed)
        }

        assertTrue(scope.isClosed())
    }

    @Test
    fun rejectsRegistrationAfterClose() {
        val scope = VpnResourceScope()
        var resourceClosed = false

        scope.close()

        try {
            scope.register(
                TestResource { resourceClosed = true }
            )
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            assertTrue(resourceClosed)
        }
    }
}
