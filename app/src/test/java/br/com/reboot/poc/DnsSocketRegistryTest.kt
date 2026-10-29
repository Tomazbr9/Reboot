package br.com.reboot.poc

import br.com.reboot.poc.infrastructure.dns.DnsSocketRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DnsSocketRegistryTest {

    @Test
    fun registersAndUnregistersSocket() {
        val registry = DnsSocketRegistry()
        val socket = DatagramSocket(null)

        try {
            registry.register(socket)
            assertEquals(1, registry.activeCount())

            registry.unregister(socket)
            assertEquals(0, registry.activeCount())
        } finally {
            socket.close()
            registry.close()
        }
    }

    @Test
    fun closesAllRegisteredSockets() {
        val registry = DnsSocketRegistry()
        val first = DatagramSocket(null)
        val second = DatagramSocket(null)

        registry.register(first)
        registry.register(second)

        registry.close()

        assertTrue(first.isClosed)
        assertTrue(second.isClosed)
        assertEquals(0, registry.activeCount())
        assertTrue(registry.isClosed())
    }

    @Test
    fun rejectsSocketAfterClose() {
        val registry = DnsSocketRegistry()
        registry.close()

        val socket = DatagramSocket(null)

        try {
            registry.register(socket)
            fail("Expected SocketException")
        } catch (_: SocketException) {
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun closeIsIdempotent() {
        val registry = DnsSocketRegistry()

        registry.close()
        registry.close()

        assertTrue(registry.isClosed())
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun closesSocketDuringBlockingReceive() {
        val registry = DnsSocketRegistry()
        val socket = DatagramSocket(0)

        registry.register(socket)

        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val interruptedByClose = AtomicBoolean(false)

        val receiver = thread {
            try {
                val buffer = ByteArray(64)

                started.countDown()

                socket.receive(
                    java.net.DatagramPacket(
                        buffer,
                        buffer.size
                    )
                )
            } catch (_: SocketException) {
                interruptedByClose.set(true)
            } finally {
                finished.countDown()
            }
        }

        try {
            assertTrue(
                started.await(2, TimeUnit.SECONDS)
            )

            registry.close()

            assertTrue(
                finished.await(2, TimeUnit.SECONDS)
            )

            assertTrue(interruptedByClose.get())
        } finally {
            registry.close()
            receiver.join(2000)
        }
    }
}
