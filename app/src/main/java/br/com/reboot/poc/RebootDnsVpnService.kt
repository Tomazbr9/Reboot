package br.com.reboot.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RebootDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "br.com.reboot.poc.START"
        const val ACTION_STOP = "br.com.reboot.poc.STOP"

        private const val CHANNEL = "vpn_poc"
        private const val TAG = "RebootDnsPoc"
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var dispatcher: DnsRelayDispatcher? = null

    private val running = AtomicBoolean(false)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> shutdown()
        }

        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (running.get()) return

        promoteToForeground()

        try {
            check(prepare(this) == null) {
                "Permissão VPN ausente"
            }

            val fd = Builder()
                .setSession("Reboot DNS - PoC")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addDnsServer("10.111.0.2")
                .addRoute("10.111.0.2", 32)
                .establish()
                ?: error("Falha ao estabelecer VPN")

            tunnel = fd

            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)

            val relay = DnsRelayDispatcher(
                forwarder = DnsUpstreamForwarder(this),
                output = output
            )

            dispatcher = relay
            running.set(true)

            Log.i(
                TAG,
                "TUN estabelecido; DNS relay concorrente ativo"
            )

            readerThread = thread(
                name = "tun-reader",
                isDaemon = true
            ) {
                readTun(input, relay)
            }

        } catch (ex: Exception) {
            Log.e(TAG, "VPN não iniciada", ex)
            shutdown()
        }
    }

    private fun readTun(
        input: FileInputStream,
        relay: DnsRelayDispatcher
    ) {
        val buffer = ByteArray(32767)

        var packets = 0L
        var udp53 = 0L
        var other = 0L
        var malformed = 0L

        var exitReason = "unknown"

        try {
            while (running.get()) {
                val count = input.read(buffer)

                if (count == -1) {
                    exitReason = "eof"
                    break
                }

                if (count == 0) continue

                packets++

                when (
                    DnsPacketClassifier.classify(
                        buffer,
                        count
                    )
                ) {
                    PacketKind.DNS_UDP_53 -> {
                        udp53++

                        val query = DnsQueryExtractor.extract(
                            buffer,
                            count
                        )

                        if (query != null) {
                            relay.submit(
                                buffer,
                                count,
                                query
                            )
                        } else {
                            Log.w(
                                TAG,
                                "dns_query_extraction_failed"
                            )
                        }
                    }

                    PacketKind.OTHER -> other++
                    PacketKind.MALFORMED -> malformed++
                }

                Log.i(
                    TAG,
                    "tun_stats " +
                            "packets=$packets " +
                            "udp53=$udp53 " +
                            "other=$other " +
                            "malformed=$malformed"
                )
            }

            if (exitReason == "unknown") {
                exitReason = "stop-requested"
            }

        } catch (ex: Exception) {
            exitReason = if (running.get()) {
                Log.e(TAG, "Erro de leitura TUN", ex)
                "read-error"
            } else {
                "stop-requested"
            }

        } finally {
            Log.i(
                TAG,
                "Leitor TUN encerrado " +
                        "reason=$exitReason " +
                        "packets=$packets " +
                        "udp53=$udp53 " +
                        "other=$other " +
                        "malformed=$malformed"
            )

            if (running.get()) {
                Handler(mainLooper).post {
                    if (running.get()) {
                        Log.w(
                            TAG,
                            "Encerrando VPN após fim do leitor TUN"
                        )
                        shutdown()
                    }
                }
            }
        }
    }

    private fun promoteToForeground() {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "VPN de teste",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = Notification.Builder(
            this,
            CHANNEL
        )
            .setContentTitle("Reboot — VPN de laboratório")
            .setContentText(
                "Filtragem DNS experimental ativa"
            )
            .setSmallIcon(
                android.R.drawable.stat_sys_warning
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1001,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(1001, notification)
        }
    }

    private fun shutdown() {
        running.set(false)

        dispatcher?.close()
        dispatcher = null

        try {
            tunnel?.close()
        } catch (_: Exception) {
        }

        tunnel = null

        readerThread?.interrupt()
        readerThread = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
