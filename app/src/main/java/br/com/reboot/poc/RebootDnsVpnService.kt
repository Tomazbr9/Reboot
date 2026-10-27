package br.com.reboot.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
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
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> {
                if (running.get()) return START_NOT_STICKY
                promoteToForeground()
                try {
                    check(prepare(this) == null) { "Permissão VPN ausente" }
                    val fd = Builder()
                        .setSession("Reboot DNS - PoC sem forwarding")
                        .setMtu(1500)
                        .addAddress("10.111.0.1", 32)
                        .addDnsServer("10.111.0.2")
                        .addRoute("10.111.0.2", 32)
                        .establish() ?: error("establish() retornou null")
                    tunnel = fd
                    running.set(true)
                    Log.i(TAG, "TUN estabelecido; DNS ainda NAO encaminhado")

                    readerThread = thread(
                        name = "tun-reader",
                        isDaemon = true
                    ) {
                        val buffer = ByteArray(32767)

                        var packets = 0L
                        var dnsPackets = 0L
                        var otherPackets = 0L
                        var malformedPackets = 0L

                        var exitReason = "unknown"

                        try {
                            // O fechamento do descriptor continua
                            // sendo responsabilidade de shutdown().
                            val stream = FileInputStream(fd.fileDescriptor)

                            while (running.get()) {
                                val count = stream.read(buffer)

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
                                    PacketKind.DNS_UDP_53 -> dnsPackets++
                                    PacketKind.OTHER -> otherPackets++
                                    PacketKind.MALFORMED -> malformedPackets++
                                }

                                Log.i(
                                    TAG,
                                    "tun_stats " +
                                            "packets=$packets " +
                                            "udp53=$dnsPackets " +
                                            "other=$otherPackets " +
                                            "malformed=$malformedPackets"
                                )

                                // Ainda não encaminhamos os pacotes.
                                // Esta etapa é somente diagnóstico.
                            }

                            if (exitReason == "unknown") {
                                exitReason = "stop-requested"
                            }

                        } catch (ex: Exception) {
                            exitReason = if (running.get()) {
                                Log.e(TAG, "Falha na leitura TUN", ex)
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
                                        "udp53=$dnsPackets " +
                                        "other=$otherPackets " +
                                        "malformed=$malformedPackets"
                            )

                            if (running.get()) {
                                // Saída inesperada: não deixar
                                // uma VPN sem leitor em funcionamento.
                                android.os.Handler(mainLooper).post {
                                    if (running.get()) {
                                        Log.w(
                                            TAG,
                                            "Encerrando VPN: leitor TUN terminou"
                                        )
                                        shutdown()
                                    }
                                }
                            }
                        }
                    }

                } catch (ex: Exception) {
                    Log.e(TAG, "VPN não iniciada", ex)
                    shutdown()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "VPN de teste", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Reboot — VPN de laboratório")
            .setContentText("Sem encaminhamento DNS. Desative após o teste.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            @Suppress("DEPRECATION")
            startForeground(1001, note)
        }
    }

    private fun shutdown() {
        running.set(false)
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        readerThread?.interrupt()
        readerThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { shutdown(); super.onRevoke() }
    override fun onDestroy() { shutdown(); super.onDestroy() }
    override fun onBind(intent: Intent?) = super.onBind(intent)
}
