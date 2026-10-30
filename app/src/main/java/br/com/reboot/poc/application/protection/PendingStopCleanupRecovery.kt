package br.com.reboot.poc.application.protection

import br.com.reboot.poc.application.ports.PendingStopCleanupRepository
import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository

enum class PendingStopRecoveryResult {
    NO_PENDING_CLEANUP,
    WAITING_FOR_VERIFICATION,
    VPN_STILL_ACTIVE,
    SESSION_MISMATCH,
    CLEANED
}

/**
 * Recupera registros de um encerramento incompleto.
 *
 * Nunca inicia ou encerra uma VPN.
 *
 * A confirmação de que a VPN está inativa deve
 * vir de uma verificação operacional confiável,
 * externa a esta classe.
 */
class PendingStopCleanupRecovery(
    private val pending: PendingStopCleanupRepository,
    private val sessions: ProtectionSessionRecordRepository,
    private val unlocks: PersistentUnlockRepository
) {

    @Synchronized
    fun recover(
        snapshot: VpnOperationalSnapshot
    ): PendingStopRecoveryResult {

        val marker = pending.load()
            ?: return PendingStopRecoveryResult.NO_PENDING_CLEANUP

        when (snapshot) {
            VpnOperationalSnapshot.Unknown -> {
                return PendingStopRecoveryResult
                    .WAITING_FOR_VERIFICATION
            }

            is VpnOperationalSnapshot.Active -> {
                return PendingStopRecoveryResult
                    .VPN_STILL_ACTIVE
            }

            VpnOperationalSnapshot.Inactive -> Unit
        }

        val savedSession = sessions.load()

        // Se houver outra sessão registrada, não
        // apagamos nada: pode ser uma sessão nova.
        if (
            savedSession != null &&
            savedSession.sessionKey != marker.sessionKey
        ) {
            return PendingStopRecoveryResult.SESSION_MISMATCH
        }

        val savedUnlock = unlocks.load()

        // Remove apenas a solicitação associada
        // à sessão cujo encerramento ficou pendente.
        if (
            savedUnlock?.protectionSessionKey ==
            marker.sessionKey
        ) {
            unlocks.clear()
        }

        if (savedSession != null) {
            sessions.clear()
        }

        // Apagar o marcador por último.
        // Se uma operação anterior falhar, ele
        // permanece disponível para nova tentativa.
        pending.clear()

        return PendingStopRecoveryResult.CLEANED
    }
}
