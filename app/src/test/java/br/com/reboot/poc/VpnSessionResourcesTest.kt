package br.com.reboot.poc

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VpnSessionResourcesTest {

    @Test
    fun retainsSessionIdentity() {
        val session = VpnSessionResources(42L)

        assertEquals(42L, session.sessionId)
        assertFalse(session.isStopped())

        session.close()

        assertTrue(session.isStopped())
    }

    @Test
    fun closesRegisteredResources() {
        val session = VpnSessionResources(1L)
        val closed = mutableListOf<String>()

        session.register(
            AutoCloseable { closed.add("tunnel") }
        )

        session.register(
            AutoCloseable { closed.add("dispatcher") }
        )

        session.close()

        assertEquals(
            listOf("dispatcher", "tunnel"),
            closed
        )
    }

    @Test
    fun closesOnlyOwnResources() {
        val first = VpnSessionResources(1L)
        val second = VpnSessionResources(2L)

        var firstClosed = false
        var secondClosed = false

        first.register(
            AutoCloseable { firstClosed = true }
        )

        second.register(
            AutoCloseable { secondClosed = true }
        )

        first.close()

        assertTrue(firstClosed)
        assertFalse(secondClosed)

        second.close()
        assertTrue(secondClosed)
    }

    @Test
    fun closeIsIdempotent() {
        val session = VpnSessionResources(5L)
        var calls = 0

        session.register(
            AutoCloseable { calls++ }
        )

        session.close()
        session.close()

        assertEquals(1, calls)
    }

    @Test
    fun interruptsReaderOnClose() {
        val session = VpnSessionResources(7L)

        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)

        val reader = thread(start = false) {
            ready.countDown()

            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {
                interrupted.set(true)
            } finally {
                finished.countDown()
            }
        }

        session.setReader(reader)

        try {
            reader.start()

            assertTrue(
                ready.await(2, TimeUnit.SECONDS)
            )

            session.close()

            assertTrue(
                finished.await(2, TimeUnit.SECONDS)
            )

            assertTrue(interrupted.get())
        } finally {
            session.close()
            reader.join(2000)
        }
    }

    @Test
    fun rejectsResourcesAfterClose() {
        val session = VpnSessionResources(8L)
        var resourceClosed = false

        session.close()

        try {
            session.register(
                AutoCloseable {
                    resourceClosed = true
                }
            )
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            assertTrue(resourceClosed)
        }
    }

    @Test
    fun rejectsSecondReader() {
        val session = VpnSessionResources(9L)

        val first = Thread {}
        val second = Thread {}

        session.setReader(first)

        try {
            session.setReader(second)
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // Comportamento esperado.
        } finally {
            session.close()
        }
    }


    @Test
    fun readerTerminatesAfterClose() {
        val session = VpnSessionResources(10L)

        val ready = CountDownLatch(1)

        val reader = thread(start = false) {
            ready.countDown()

            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        session.setReader(reader)
        reader.start()

        assertTrue(
            ready.await(2, TimeUnit.SECONDS)
        )

        session.close()

        assertTrue(
            session.awaitReaderTermination(2000)
        )
    }

    @Test
    fun sessionDoesNotCloseOtherSessionsResources() {
        val first = VpnSessionResources(11L)
        val second = VpnSessionResources(12L)

        var secondResourceClosed = false

        second.register(
            AutoCloseable {
                secondResourceClosed = true
            }
        )

        first.close()

        assertFalse(secondResourceClosed)
        assertFalse(second.isStopped())

        second.close()

        assertTrue(secondResourceClosed)
    }

}
