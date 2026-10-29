package br.com.reboot.poc.infrastructure.dns

import java.net.DatagramSocket
import java.net.SocketException

/**
 * Controla os sockets DNS pertencentes a uma
 * execução do encaminhador.
 */
class DnsSocketRegistry : AutoCloseable {

    private val lock = Any()
    private val sockets = mutableSetOf<DatagramSocket>()
    private var closed = false

    @Throws(SocketException::class)
    fun register(socket: DatagramSocket) {
        synchronized(lock) {
            if (closed) {
                socket.close()
                throw SocketException(
                    "DNS registry already closed"
                )
            }

            sockets.add(socket)
        }
    }

    fun unregister(socket: DatagramSocket) {
        synchronized(lock) {
            sockets.remove(socket)
        }
    }

    fun activeCount(): Int {
        return synchronized(lock) {
            sockets.size
        }
    }

    fun isClosed(): Boolean {
        return synchronized(lock) {
            closed
        }
    }

    override fun close() {
        val toClose: List<DatagramSocket>

        synchronized(lock) {
            if (closed) return

            closed = true
            toClose = sockets.toList()
            sockets.clear()
        }

        // Fecha fora do lock para não bloquear
        // registro ou limpeza desnecessariamente.
        toClose.forEach { socket ->
            socket.close()
        }
    }
}
