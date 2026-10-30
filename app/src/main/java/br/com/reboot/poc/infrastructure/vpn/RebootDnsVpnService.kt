package br.com.reboot.poc.infrastructure.vpn

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import br.com.reboot.poc.application.protection.ProtectionRecoveryCoordinator
import br.com.reboot.poc.application.protection.ProtectionRecoveryEvaluator
import br.com.reboot.poc.application.protection.ProtectionRecoveryResult
import br.com.reboot.poc.application.protection.ProtectionSessionRecorder
import br.com.reboot.poc.application.protection.VpnOperationalSnapshot
import br.com.reboot.poc.application.unlock.PersistentUnlockCoordinator
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import br.com.reboot.poc.domain.unlock.UnlockDelay
import br.com.reboot.poc.domain.unlock.UnlockRequestState
import br.com.reboot.poc.infrastructure.dns.DnsPacketClassifier
import br.com.reboot.poc.infrastructure.dns.DnsQueryExtractor
import br.com.reboot.poc.infrastructure.dns.DnsRelayDispatcher
import br.com.reboot.poc.infrastructure.dns.DnsUpstreamForwarder
import br.com.reboot.poc.infrastructure.dns.PacketKind
import br.com.reboot.poc.infrastructure.persistence.ProtectionSessionRecordStorage
import br.com.reboot.poc.infrastructure.persistence.UnlockRequestStorage
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread
import br.com.reboot.poc.application.unlock.UnlockStopAuthorization
import br.com.reboot.poc.application.unlock.UnlockStopDecision
import br.com.reboot.poc.domain.protection.PendingStopCleanup
import br.com.reboot.poc.infrastructure.persistence.PendingStopCleanupStorage
import br.com.reboot.poc.application.protection.ProtectionStopCleanupResult
class RebootDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "br.com.reboot.poc.START"
        const val ACTION_STOP = "br.com.reboot.poc.STOP"

        const val ACTION_REQUEST_UNLOCK =
            "br.com.reboot.poc.REQUEST_UNLOCK"

        const val EXTRA_UNLOCK_DELAY =
            "br.com.reboot.poc.UNLOCK_DELAY"

        const val ACTION_GET_UNLOCK_STATUS =
            "br.com.reboot.poc.GET_UNLOCK_STATUS"

        const val ACTION_CANCEL_UNLOCK =
            "br.com.reboot.poc.CANCEL_UNLOCK"

        const val ACTION_CONFIRM_UNLOCK =
            "br.com.reboot.poc.CONFIRM_UNLOCK"

        const val EXTRA_REQUEST_ID =
            "br.com.reboot.poc.REQUEST_ID"

        const val EXTRA_RESULT_RECEIVER =
            "br.com.reboot.poc.RESULT_RECEIVER"

        const val RESULT_STATUS = 1

        const val KEY_STATE = "state"
        const val KEY_REMAINING_MILLIS = "remainingMillis"
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_MESSAGE = "message"

        private const val CHANNEL = "vpn_poc"
        private const val TAG = "RebootDnsPoc"

    }

    private val sessionGuard = VpnSessionGuard()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val protectionStorage by lazy {
        ProtectionSessionRecordStorage(this)
    }

    private val unlockStorage by lazy {
        UnlockRequestStorage(this)
    }

    private val pendingStopStorage by lazy {
        PendingStopCleanupStorage(this)
    }

    private val sessionRecorder by lazy {
        ProtectionSessionRecorder(
            sessions = protectionStorage,
            unlocks = unlockStorage
        )
    }

    private val recoveryCoordinator by lazy {
        ProtectionRecoveryCoordinator(
            sessions = protectionStorage,
            unlocks = unlockStorage
        )
    }

    private val snapshotResolver by lazy {
        VpnOperationalSnapshotResolver(sessionGuard)
    }

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

            ACTION_REQUEST_UNLOCK -> {
                requestUnlock(intent)
            }

            ACTION_GET_UNLOCK_STATUS -> {
                getUnlockStatus(intent)
            }

            ACTION_CANCEL_UNLOCK -> {
                cancelUnlock(intent)
            }

            ACTION_CONFIRM_UNLOCK -> {
                confirmUnlock(intent)
            }

            ACTION_STOP -> {
                Log.w(
                    TAG,
                    "vpn_direct_stop_rejected " +
                            "reason=unlock_confirmation_required"
                )
            }

            else -> {
                Log.w(TAG, "vpn_unknown_command")
            }
        }

        if (currentSession == null) {
            stopSelfResult(startId)
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

            recoverActiveSession(session)

            sessionRecorder.onActivated(
                ProtectionSessionRecord(
                    sessionKey = session.sessionKey,
                    bootCount = currentBootCount(),
                    activatedAtElapsedMillis =
                        SystemClock.elapsedRealtime()
                )
            )

            Log.i(
                TAG,
                "vpn_session_persisted id=$sessionId"
            )


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
    ): VpnStopResult {

        check(Looper.myLooper() == Looper.getMainLooper()) {
            "stopSession must run on main thread"
        }

        if (currentSession !== session) {
            Log.w(
                TAG,
                "vpn_stale_stop_ignored id=${session.sessionId}"
            )
            return VpnStopResult.STALE_SESSION
        }

        val sessionId = session.sessionId

        if (!sessionGuard.ensureStopping(sessionId)) {
            Log.w(
                TAG,
                "vpn_stop_state_mismatch id=$sessionId"
            )
            return VpnStopResult.INVALID_STATE
        }

        Log.i(
            TAG,
            "vpn_session_stopping id=$sessionId reason=$reason"
        )

        var markerSaved = false
        var resourceCleanupSucceeded = false
        var persistenceCleanupSucceeded = false
        var foregroundCleanupSucceeded = false

        // 1. Registrar a limpeza pendente ANTES
        // de fechar os recursos.
        try {
            pendingStopStorage.save(
                PendingStopCleanup(
                    sessionKey = session.sessionKey
                )
            )

            markerSaved = true

            Log.i(
                TAG,
                "vpn_stop_cleanup_marker_saved id=$sessionId"
            )
        } catch (ex: Exception) {
            Log.e(
                TAG,
                "vpn_stop_cleanup_marker_save_failed id=$sessionId",
                ex
            )
        }

        // 2. Fechar os recursos.
        // O fechamento é necessário mesmo quando
        // não conseguimos persistir o marcador.
        try {
            session.close()

            resourceCleanupSucceeded = true

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

        // 3. Só limpar os registros se o fechamento
        // dos recursos não apresentou erros.
        if (resourceCleanupSucceeded) {
            try {
                val cleanupResult =
                    sessionRecorder.onStoppedDetailed(
                        session.sessionKey
                    )

                persistenceCleanupSucceeded =
                    cleanupResult ==
                            ProtectionStopCleanupResult.CLEANED ||
                            cleanupResult ==
                            ProtectionStopCleanupResult.ALREADY_CLEAN

                Log.i(
                    TAG,
                    "vpn_session_persistence_cleanup " +
                            "id=$sessionId result=$cleanupResult"
                )

            } catch (ex: Exception) {
                Log.e(
                    TAG,
                    "vpn_session_record_cleanup_failed id=$sessionId",
                    ex
                )
            }
        } else {
            Log.w(
                TAG,
                "vpn_persistence_cleanup_deferred " +
                        "id=$sessionId reason=resource_close_failed"
            )
        }

        // 4. Remover o marcador por último.
        //
        // Se a limpeza falhou, preservamos o marcador.
        // Se sua gravação falhou, o resultado geral
        // não poderá ser considerado plenamente bem-sucedido.
        if (resourceCleanupSucceeded &&
            persistenceCleanupSucceeded &&
            markerSaved
        ) {
            try {
                pendingStopStorage.clear()

                Log.i(
                    TAG,
                    "vpn_stop_cleanup_marker_cleared id=$sessionId"
                )
            } catch (ex: Exception) {
                persistenceCleanupSucceeded = false

                Log.e(
                    TAG,
                    "vpn_stop_cleanup_marker_clear_failed id=$sessionId",
                    ex
                )
            }
        }

        if (!markerSaved) {
            persistenceCleanupSucceeded = false
        }

        // 5. Encerrar a sessão gerenciada pelo serviço.
        // Isso não comprova, por si só, que uma
        // falha anterior deixou todos os recursos fechados.
        if (currentSession === session) {
            currentSession = null
        }

        sessionGuard.finishStop(sessionId)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundCleanupSucceeded = true
        } catch (ex: Exception) {
            Log.e(
                TAG,
                "vpn_foreground_stop_failed id=$sessionId",
                ex
            )
        }

        // 6. Classificar o encerramento.
        val result = VpnStopResultResolver.resolve(
            resourceCleanupSucceeded = resourceCleanupSucceeded,
            persistenceCleanupSucceeded = persistenceCleanupSucceeded,
            foregroundCleanupSucceeded = foregroundCleanupSucceeded
        )

        Log.i(
            TAG,
            "vpn_session_stopped " +
                    "id=$sessionId " +
                    "key=${session.sessionKey} " +
                    "reason=$reason " +
                    "result=$result"
        )

        if (requestStop) {
            stopSelfResult(latestStartId)
        }

        return result
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
                R.drawable.stat_sys_warning
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


    override fun onCreate() {
        super.onCreate()

        try {
            val saved = protectionStorage.load()

            val decision = ProtectionRecoveryEvaluator()
                .evaluate(
                    saved = saved,
                    snapshot = VpnOperationalSnapshot.Unknown,
                    currentBootCount = currentBootCount()
                )

            Log.i(
                TAG,
                "vpn_recovery_initial_assessment " +
                        "decision=$decision " +
                        "hasSavedRecord=${saved != null}"
            )
        } catch (ex: Exception) {
            Log.e(
                TAG,
                "vpn_recovery_initial_assessment_failed",
                ex
            )
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


    private fun currentBootCount(): Int =
        Settings.Global.getInt(
            contentResolver,
            Settings.Global.BOOT_COUNT
        )

    private fun recoverActiveSession(
        session: VpnSessionResources
    ): ProtectionRecoveryResult {

        check(currentSession === session) {
            "Stale VPN session cannot recover protection"
        }

        val snapshot = snapshotResolver.resolve(session)

        check(
            snapshot is VpnOperationalSnapshot.Active &&
                    snapshot.sessionKey == session.sessionKey
        ) {
            "VPN session is not operationally active"
        }

        val result = recoveryCoordinator.recover(
            snapshot = snapshot,
            currentBootCount = currentBootCount()
        )

        Log.i(
            TAG,
            "vpn_recovery_completed " +
                    "id=${session.sessionId} " +
                    "result=$result"
        )

        return result
    }

    private val unlockCoordinator by lazy {
        PersistentUnlockCoordinator(
            repository = unlockStorage,
            elapsedRealtimeMillis = {
                SystemClock.elapsedRealtime()
            },
            bootCount = {
                currentBootCount()
            }
        )
    }


    private fun requestUnlock(intent: Intent) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Unlock request must run on main thread"
        }

        val session = currentSession

        // 1. Verifica se existe uma VPN ativa.
        if (
            session == null ||
            session.isStopped() ||
            !sessionGuard.isActive(session.sessionId)
        ) {
            Log.w(
                TAG,
                "vpn_unlock_request_rejected reason=no_active_session"
            )

            reply(
                intent,
                state = "INACTIVE",
                message = "Nenhuma sessão VPN ativa."
            )

            return
        }

        // 2. Lê o prazo enviado pela interface.
        val delayName = intent.getStringExtra(
            EXTRA_UNLOCK_DELAY
        )

        val delay = UnlockDelay.entries.firstOrNull {
            it.name == delayName
        }

        // 3. Rejeita um prazo inexistente.
        if (delay == null) {
            Log.w(
                TAG,
                "vpn_unlock_request_rejected reason=invalid_delay"
            )

            reply(
                intent,
                state = "ERROR",
                message = "Prazo de desbloqueio inválido."
            )

            return
        }

        try {
            // 4. Cria ou recupera o pedido persistido.
            val status = unlockCoordinator.requestUnlock(
                protectionSessionKey = session.sessionKey,
                delay = delay
            )

            Log.i(
                TAG,
                "vpn_unlock_requested " +
                        "session=${session.sessionKey} " +
                        "request=${status.requestId} " +
                        "state=${status.state} " +
                        "remainingMillis=${status.remainingMillis}"
            )

            // 5. Devolve o resultado à MainActivity.
            reply(
                intent,
                state = status.state.name,
                requestId = status.requestId,
                remainingMillis = status.remainingMillis
            )

        } catch (ex: Exception) {
            Log.e(
                TAG,
                "vpn_unlock_request_failed",
                ex
            )

            reply(
                intent,
                state = "ERROR",
                message = "Não foi possível solicitar o desbloqueio."
            )
        }
    }

    private fun activeUnlockSession(): VpnSessionResources? {
        val session = currentSession ?: return null

        if (
            session.isStopped() ||
            !sessionGuard.isActive(session.sessionId)
        ) {
            return null
        }

        return session
    }

    @Suppress("DEPRECATION")
    private fun reply(
        intent: Intent,
        state: String,
        message: String = "",
        requestId: String? = null,
        remainingMillis: Long = 0L
    ) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(
            EXTRA_RESULT_RECEIVER
        ) ?: return

        receiver.send(
            RESULT_STATUS,
            Bundle().apply {
                putString(KEY_STATE, state)
                putString(KEY_MESSAGE, message)
                putString(KEY_REQUEST_ID, requestId)
                putLong(
                    KEY_REMAINING_MILLIS,
                    remainingMillis
                )
            }
        )
    }

    private fun getUnlockStatus(intent: Intent) {
        val session = activeUnlockSession()

        if (session == null) {
            reply(
                intent,
                state = "INACTIVE",
                message = "Nenhuma sessão VPN ativa."
            )
            return
        }

        try {
            val status = unlockCoordinator.getStatus(
                session.sessionKey
            )

            if (status == null) {
                reply(
                    intent,
                    state = "PROTECTED",
                    message = "Proteção ativa."
                )
                return
            }

            reply(
                intent,
                state = status.state.name,
                requestId = status.requestId,
                remainingMillis = status.remainingMillis
            )
        } catch (ex: Exception) {
            Log.e(TAG, "vpn_unlock_status_failed", ex)
            reply(
                intent,
                state = "ERROR",
                message = "Não foi possível consultar o pedido."
            )
        }
    }

    private fun cancelUnlock(intent: Intent) {
        val session = activeUnlockSession()

        if (session == null) {
            reply(intent, "INACTIVE")
            return
        }

        val requestId = intent.getStringExtra(
            EXTRA_REQUEST_ID
        )

        if (requestId.isNullOrBlank()) {
            reply(intent, "ERROR", "Pedido inválido.")
            return
        }

        try {
            val cancelled = unlockCoordinator.cancel(
                protectionSessionKey = session.sessionKey,
                requestId = requestId
            )

            if (cancelled) {
                Log.i(TAG, "vpn_unlock_cancelled")
                reply(
                    intent,
                    state = "PROTECTED",
                    message = "Solicitação cancelada."
                )
            } else {
                reply(
                    intent,
                    state = "ERROR",
                    message = "Solicitação não encontrada."
                )
            }
        } catch (ex: Exception) {
            Log.e(TAG, "vpn_unlock_cancel_failed", ex)
            reply(intent, "ERROR", "Falha ao cancelar.")
        }
    }

    private fun confirmUnlock(intent: Intent) {
        val session = activeUnlockSession()

        if (session == null) {
            reply(intent, "INACTIVE")
            return
        }

        val requestId = intent.getStringExtra(
            EXTRA_REQUEST_ID
        )

        if (requestId.isNullOrBlank()) {
            reply(intent, "ERROR", "Pedido inválido.")
            return
        }

        try {
            // Uma consulta antiga da interface não
            // autoriza a parada. Validamos novamente.
            val decision = stopAuthorization.evaluate(
                activeSessionKey = session.sessionKey,
                requestId = requestId,
                vpnIsActive =
                    currentSession === session &&
                            !session.isStopped() &&
                            sessionGuard.isActive(session.sessionId)
            )

            if (decision != UnlockStopDecision.AUTHORIZED) {
                Log.w(
                    TAG,
                    "vpn_unlock_confirmation_rejected " +
                            "reason=$decision"
                )

                getUnlockStatus(intent)
                return
            }

            Log.i(
                TAG,
                "vpn_unlock_confirmation_accepted " +
                        "session=${session.sessionKey}"
            )


            val stopResult = stopSession(
                session = session,
                reason = "unlock-confirmed",
                requestStop = true
            )

            when (stopResult) {
                VpnStopResult.STOPPED -> {
                    reply(
                        intent,
                        state = "INACTIVE",
                        message = "Proteção desativada."
                    )
                }

                VpnStopResult.STOPPED_WITH_ERRORS -> {
                    reply(
                        intent,
                        state = "ERROR",
                        message =
                            "Houve uma falha durante o encerramento. " +
                                    "Verifique o estado da VPN no Android."
                    )
                }

                VpnStopResult.STALE_SESSION,
                VpnStopResult.INVALID_STATE -> {
                    Log.w(
                        TAG,
                        "vpn_unlock_stop_not_completed " +
                                "result=$stopResult"
                    )

                    getUnlockStatus(intent)
                }
            }

        } catch (ex: Exception) {
            Log.e(
                TAG,
                "vpn_unlock_confirmation_failed",
                ex
            )

            reply(
                intent,
                state = "ERROR",
                message = "Não foi possível confirmar a desativação."
            )
        }
    }
    private val stopAuthorization by lazy {
        UnlockStopAuthorization(
            repository = unlockStorage,
            elapsedRealtimeMillis = {
                SystemClock.elapsedRealtime()
            },
            bootCount = {
                currentBootCount()
            }
        )
    }


}
