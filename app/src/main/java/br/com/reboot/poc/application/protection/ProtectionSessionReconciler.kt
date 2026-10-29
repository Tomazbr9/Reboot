package br.com.reboot.poc.application.protection

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository

enum class SessionReconciliationResult {
    NO_RECORD,
    WAITING_FOR_VERIFICATION,
    MATCHED_ACTIVE_SESSION,
    STALE_RECORD_CLEARED
}

class ProtectionSessionReconciler(
    private val sessions: ProtectionSessionRecordRepository,
    private val unlocks: PersistentUnlockRepository
) {

    @Synchronized
    fun reconcile(
        snapshot: VpnOperationalSnapshot,
        currentBootCount: Int
    ): SessionReconciliationResult {
        require(currentBootCount >= 0)

        val saved = sessions.load()
            ?: return SessionReconciliationResult.NO_RECORD

        // Nunca remover dados enquanto o estado real
        // da VPN não foi confirmado.
        if (snapshot is VpnOperationalSnapshot.Unknown) {
            return SessionReconciliationResult
                .WAITING_FOR_VERIFICATION
        }

        val matches = when (snapshot) {
            is VpnOperationalSnapshot.Active ->
                saved.sessionKey == snapshot.sessionKey &&
                        saved.bootCount == currentBootCount

            VpnOperationalSnapshot.Inactive -> false

            VpnOperationalSnapshot.Unknown -> false
        }

        if (matches) {
            return SessionReconciliationResult
                .MATCHED_ACTIVE_SESSION
        }

        // Só limpar o desbloqueio se pertencer
        // à sessão que se tornou obsoleta.
        val unlock = unlocks.load()

        if (
            unlock?.protectionSessionKey ==
            saved.sessionKey
        ) {
            unlocks.clear()
        }

        sessions.clear()

        return SessionReconciliationResult
            .STALE_RECORD_CLEARED
    }
}
