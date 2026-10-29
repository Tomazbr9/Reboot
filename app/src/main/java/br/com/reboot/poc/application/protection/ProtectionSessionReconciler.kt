package br.com.reboot.poc.application.protection

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository

enum class SessionReconciliationResult {
    NO_RECORD,
    MATCHED_ACTIVE_SESSION,
    STALE_RECORD_CLEARED
}

class ProtectionSessionReconciler(
    private val sessions:
    ProtectionSessionRecordRepository,
    private val unlocks:
    PersistentUnlockRepository
) {

    /**
     * liveSessionKey só pode ser fornecida após
     * verificar que o serviço VPN está ACTIVE.
     *
     * null significa que não há uma sessão ativa
     * confirmada, não que a VPN foi desligada
     * apenas porque a Activity não a encontrou.
     */
    @Synchronized
    fun reconcile(
        liveSessionKey: String?,
        currentBootCount: Int
    ): SessionReconciliationResult {
        require(currentBootCount >= 0)

        val saved = sessions.load()
            ?: return SessionReconciliationResult.NO_RECORD

        val matches =
            saved.bootCount == currentBootCount &&
                    saved.sessionKey == liveSessionKey

        if (matches) {
            return SessionReconciliationResult
                .MATCHED_ACTIVE_SESSION
        }

        // O registro antigo não corresponde à
        // sessão operacional confirmada.
        //
        // Invalide apenas o pedido associado
        // a essa sessão antiga.
        val unlock = unlocks.load()

        if (
            unlock != null &&
            unlock.protectionSessionKey ==
            saved.sessionKey
        ) {
            unlocks.clear()
        }

        sessions.clear()

        return SessionReconciliationResult
            .STALE_RECORD_CLEARED
    }
}
