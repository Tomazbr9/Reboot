
package br.com.reboot.poc

import android.util.Log
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DnsRelayDispatcher(
    private val forwarder: DnsUpstreamForwarder,
    private val output: FileOutputStream
) {
    companion object {
        private const val TAG = "RebootDnsPoc"
    }

    private val active = AtomicBoolean(true)
    private val writeLock = Any()

    private val executor = DnsTaskExecutor(
        workers = 4,
        queueCapacity = 32
    )

    private val received = AtomicLong()
    private val written = AtomicLong()
    private val failed = AtomicLong()
    private val rejected = AtomicLong()

    fun submit(
        packet: ByteArray,
        length: Int,
        query: DnsQuery
    ) {
        if (!active.get()) return

        // O leitor TUN reutiliza o buffer.
        // Copia os bytes antes de entregar ao worker.
        if (length <= 0 || length > packet.size) {
            failed.incrementAndGet()
            return
        }

        val requestCopy = packet.copyOf(length)

        // Evita compartilhar um payload mutável
        // entre a thread leitora e os workers.
        val queryCopy = query.copy(
            payload = query.payload.copyOf()
        )

        received.incrementAndGet()

        val accepted = executor.submit {
            if (active.get()) {
                process(requestCopy, queryCopy)
            }
        }

        if (!accepted && active.get()) {
            rejected.incrementAndGet()

            Log.w(
                TAG,
                "dns_relay_queue_full_or_closed"
            )
        }
    }

    private fun process(
        request: ByteArray,
        query: DnsQuery
    ) {
        try {
            val response = forwarder.forward(query)

            if (!active.get()) return

            val probe = DnsFilterProbe.inspect(
                queryPayload = query.payload,
                responsePayload = response
            )

            if (probe != null) {
                Log.i(
                    TAG,
                    "DNS_FILTER_PROBE result=$probe"
                )
            }

            val replyPacket =
                DnsResponsePacketBuilder.build(
                    request = request,
                    requestLength = request.size,
                    dnsResponse = response
                )

            if (replyPacket == null) {
                failed.incrementAndGet()

                Log.w(
                    TAG,
                    "dns_relay_packet_rejected"
                )
                return
            }

            synchronized(writeLock) {
                if (active.get()) {
                    output.write(replyPacket)

                    val total = written.incrementAndGet()

                    Log.i(
                        TAG,
                        "DNS_RELAY_OK " +
                                "tunWritten=$total " +
                                "responseBytes=${replyPacket.size}"
                    )
                }
            }

        } catch (ex: Exception) {
            if (active.get()) {
                failed.incrementAndGet()

                Log.w(
                    TAG,
                    "dns_relay_failed " +
                            "type=${ex.javaClass.simpleName}"
                )
            }
        }
    }

    fun close() {
        synchronized(writeLock) {
            if (!active.compareAndSet(true, false)) {
                return
            }

            executor.close()
            forwarder.close()

            Log.i(
                TAG,
                "dns_upstream_sockets_closed"
            )

            Log.i(
                TAG,
                "dns_relay_stopped " +
                        "received=${received.get()} " +
                        "written=${written.get()} " +
                        "failed=${failed.get()} " +
                        "rejected=${rejected.get()}"
            )
        }
    }
}
