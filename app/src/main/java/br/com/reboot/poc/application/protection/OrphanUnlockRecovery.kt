
package br.com.reboot.poc.application.protection

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository

enum class OrphanUnlockRecoveryResult {
    NO_UNLOCK_REQUEST,
    SESSION_RECORD_PRESENT,
    WAITING_FOR_VERIFICATION,
    ACTIVE_SESSION_MATCHES,
    ORPHAN_UNLOCK_CLEARED
}

/**
 * Recupera solicitações de desbloqueio
 * quando o registro da sessão está ausente.
 *
 * Não inicia, encerra ou autoriza parar VPNs.
 */
class OrphanUnlockRecovery(
    private val sessions: ProtectionSessionRecordRepository,
    private val unlocks: PersistentUnlockRepository
) {

    @Synchronized
    fun recover(
        snapshot: VpnOperationalSnapshot,
        currentBootCount: Int
    ): OrphanUnlockRecoveryResult {
        require(currentBootCount >= 0)

        val request = unlocks.load()
            ?: return OrphanUnlockRecoveryResult.NO_UNLOCK_REQUEST

        // Havendo registro da sessão, a reconciliação
        // normal é responsável por avaliá-lo.
        if (sessions.load() != null) {
            return OrphanUnlockRecoveryResult
                .SESSION_RECORD_PRESENT
        }

        if (snapshot == VpnOperationalSnapshot.Unknown) {
            return OrphanUnlockRecoveryResult
                .WAITING_FOR_VERIFICATION
        }

        // A sessão ainda pode estar ativa, apesar de
        // sua gravação persistente ter falhado.
        if (
            snapshot is VpnOperationalSnapshot.Active &&
            snapshot.sessionKey == request.protectionSessionKey &&
            request.bootCount == currentBootCount
        ) {
            return OrphanUnlockRecoveryResult
                .ACTIVE_SESSION_MATCHES
        }

        // Somente uma verificação conclusiva permite
        // descartar a solicitação sem registro associado.
        unlocks.clear()

        return OrphanUnlockRecoveryResult
            .ORPHAN_UNLOCK_CLEARED
    }
}
