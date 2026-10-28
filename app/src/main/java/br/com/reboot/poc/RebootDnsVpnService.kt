package br.com.reboot.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

class RebootDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "br.com.reboot.poc.START"
        const val ACTION_STOP = "br.com.reboot.poc.STOP"

        private const val CHANNEL = "vpn_poc"
        private const val TAG = "RebootDnsPoc"
    }

    private val sessionGuard = VpnSessionGuard()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentSession: VpnSessionResources? = null

    // Acessado na thread principal do Service.
    private var latestStartId = 0

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        latestStartId = startId

        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                currentSession?.let {
                    stopSession(it, "user-request", true)
                } ?: stopSelfResult(startId)
            }
            else -> {
                Log.w(TAG, "vpn_unknown_command")
                if (currentSession == null) {
                    stopSelfResult(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startVpn() {
        val sessionId = sessionGuard.beginStart()

        if (sessionId == null) {
            Log.w(
                TAG,
                "vpn_start_ignored state=" +
                        sessionGuard.currentState()
            )
            return
        }

        val session = VpnSessionResources(sessionId)
        currentSession = session

        Log.i(
            TAG,
            "vpn_session_created " +
                    "id=${session.sessionId} " +
                    "key=${session.sessionKey}"
        )

        try {
            promoteToForeground()

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

            // Registro imediato do túnel.
            session.register(fd)

            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)

            val relay = DnsRelayDispatcher(
                forwarder = DnsUpstreamForwarder(this),
                output = output
            )

            // O relay é fechado antes do túnel,
            // pois foi registrado depois dele.
            session.register(
                AutoCloseable { relay.close() }
            )

            val reader = thread(
                start = false,
                name = "tun-reader-$sessionId",
                isDaemon = true
            ) {
                readTun(session, input, relay)
            }

            session.setReader(reader)

            check(sessionGuard.markActive(sessionId)) {
                "Não foi possível ativar a sessão VPN"
            }

            reader.start()

            Log.i(
                TAG,
                "vpn_session_active " +
                        "id=$sessionId " +
                        "key=${session.sessionKey}"
            )

            Log.i(
                TAG,
                "TUN estabelecido; DNS relay concorrente ativo"
            )

        } catch (ex: Exception) {
            Log.e(
                TAG,
                "vpn_start_failed id=$sessionId",
                ex
            )

            stopSession(
                session,
                reason = "start-failed",
                requestStop = true
            )
        }
    }

    private fun readTun(
        session: VpnSessionResources,
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
            while (
                !session.isStopped() &&
                sessionGuard.isActive(session.sessionId)
            ) {
                val count = input.read(buffer)

                if (count == -1) {
                    exitReason = "eof"
                    break
                }

                if (count == 0) continue

                packets++

                when (
                    DnsPacketClassifier.classify(
                        buffer, count
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
            exitReason = if (session.isStopped()) {
                "stop-requested"
            } else {
                Log.e(
                    TAG,
                    "vpn_reader_error id=${session.sessionId}",
                    ex
                )
                "read-error"
            }

        } finally {
            Log.i(
                TAG,
                "Leitor TUN encerrado " +
                        "id=${session.sessionId} " +
                        "reason=$exitReason " +
                        "packets=$packets " +
                        "udp53=$udp53 " +
                        "other=$other " +
                        "malformed=$malformed"
            )

            // Um leitor antigo só pode solicitar
            // o encerramento da própria sessão.
            if (!session.isStopped()) {
                mainHandler.post {
                    if (
                        currentSession === session &&
                        sessionGuard.isActive(session.sessionId)
                    ) {
                        stopSession(
                            session,
                            reason = "reader-ended",
                            requestStop = true
                        )
                    }
                }
            }
        }
    }

    private fun stopSession(
        session: VpnSessionResources,
        reason: String,
        requestStop: Boolean
    ) {
        // Toda chamada deve ocorrer na thread principal.
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "stopSession must run on main thread"
        }

        if (currentSession !== session) {
            Log.w(
                TAG,
                "vpn_stale_stop_ignored id=${session.sessionId}"
            )
            return
        }

        val sessionId = session.sessionId

        if (!sessionGuard.ensureStopping(sessionId)) {
            Log.w(
                TAG,
                "vpn_stop_state_mismatch id=$sessionId"
            )
            return
        }

        Log.i(
            TAG,
            "vpn_session_stopping " +
                    "id=$sessionId reason=$reason"
        )

        try {
            try {
                session.close()

                Log.i(
                    TAG,
                    "vpn_resources_closed id=$sessionId"
                )
            } catch (ex: Exception) {
                Log.e(
                    TAG,
                    "vpn_resource_cleanup_failed id=$sessionId",
                    ex
                )
            }

        } finally {
            // Protege as referências contra callbacks antigos.
            if (currentSession === session) {
                currentSession = null
            }

            sessionGuard.finishStop(sessionId)

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (ex: Exception) {
                Log.w(
                    TAG,
                    "vpn_foreground_stop_failed " +
                            "type=${ex.javaClass.simpleName}"
                )
            }

            Log.i(
                TAG,
                "vpn_session_stopped " +
                        "id=$sessionId " +
                        "key=${session.sessionKey} " +
                        "reason=$reason"
            )

            if (requestStop) {
                stopSelfResult(latestStartId)
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

        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Reboot — VPN de laboratório")
            .setContentText("Filtragem DNS experimental ativa")
            .setSmallIcon(
                android.R.drawable.stat_sys_warning
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1001,
                note,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(1001, note)
        }
    }

    override fun onRevoke() {
        // O Android pode chamar onRevoke fora
        // da thread principal.
        val revokedSession = currentSession

        mainHandler.post {
            if (revokedSession != null) {
                stopSession(
                    revokedSession,
                    "permission-revoked",
                    true
                )
            }
        }

        super.onRevoke()
    }

    override fun onDestroy() {
        currentSession?.let {
            stopSession(
                it,
                "service-destroyed",
                false
            )
        }

        super.onDestroy()
    }
}
