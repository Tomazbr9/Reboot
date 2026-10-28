package br.com.reboot.poc

import android.util.Log
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DnsRelayDispatcher(
    private val forwarder: DnsUpstreamForwarder,
    private val output: FileOutputStream
) {
    companion object {
        private const val TAG = "RebootDnsPoc"
        private const val WORKERS = 4
        private const val QUEUE_CAPACITY = 32
    }

    private val active = AtomicBoolean(true)
    private val writeLock = Any()

    private val received = AtomicLong()
    private val written = AtomicLong()
    private val failed = AtomicLong()
    private val rejected = AtomicLong()

    private val executor = ThreadPoolExecutor(
        WORKERS,
        WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { task ->
            Thread(task, "dns-relay-worker").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(
        packet: ByteArray,
        length: Int,
        query: DnsQuery
    ) {
        if (!active.get()) return

        // A thread leitora reutiliza seu buffer.
        // Cada worker precisa de uma cópia independente.
        val requestCopy = packet.copyOf(length)
        received.incrementAndGet()

        try {
            executor.execute {
                if (!active.get()) return@execute

                try {
                    val response = forwarder.forward(query)

                    if (!active.get()) return@execute

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
                            request = requestCopy,
                            requestLength = requestCopy.size,
                            dnsResponse = response
                        )

                    if (replyPacket == null) {
                        failed.incrementAndGet()
                        Log.w(
                            TAG,
                            "dns_relay_packet_rejected"
                        )
                        return@execute
                    }

                    // Vários workers não podem escrever
                    // simultaneamente no mesmo TUN.
                    synchronized(writeLock) {
                        if (!active.get()) return@synchronized

                        output.write(replyPacket)

                        val total = written.incrementAndGet()

                        Log.i(
                            TAG,
                            "DNS_RELAY_OK " +
                                    "tunWritten=$total " +
                                    "responseBytes=${replyPacket.size}"
                        )
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
        } catch (_: RejectedExecutionException) {
            if (active.get()) {
                rejected.incrementAndGet()
                Log.w(
                    TAG,
                    "dns_relay_queue_full"
                )
            }
        }
    }

    fun close() {
        synchronized(writeLock) {
            if (!active.compareAndSet(true, false)) {
                return
            }

            executor.shutdownNow()

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
