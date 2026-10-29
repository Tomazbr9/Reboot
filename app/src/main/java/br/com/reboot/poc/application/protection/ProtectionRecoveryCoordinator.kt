
package br.com.reboot.poc.application.protection

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository

enum class ProtectionRecoveryResult {
    WAITING_FOR_VERIFICATION,
    NO_SAVED_DATA,
    ACTIVE_SESSION_MATCHED,
    STALE_SESSION_CLEARED,
    ORPHAN_UNLOCK_PRESERVED,
    ORPHAN_UNLOCK_CLEARED
}

/**
 * Ponto único de entrada para recuperação
 * dos registros de proteção e desbloqueio.
 *
 * Não inicia nem encerra a VPN.
 */
class ProtectionRecoveryCoordinator(
    private val sessions: ProtectionSessionRecordRepository,
    private val unlocks: PersistentUnlockRepository
) {

    private val reconciler = ProtectionSessionReconciler(
        sessions = sessions,
        unlocks = unlocks
    )

    private val orphanRecovery = OrphanUnlockRecovery(
        sessions = sessions,
        unlocks = unlocks
    )

    @Synchronized
    fun recover(
        snapshot: VpnOperationalSnapshot,
        currentBootCount: Int
    ): ProtectionRecoveryResult {
        require(currentBootCount >= 0)

        // A ausência de confirmação operacional não
        // autoriza limpar registros persistidos.
        if (snapshot == VpnOperationalSnapshot.Unknown) {
            return ProtectionRecoveryResult
                .WAITING_FOR_VERIFICATION
        }

        if (sessions.load() != null) {
            return when (
                reconciler.reconcile(
                    snapshot = snapshot,
                    currentBootCount = currentBootCount
                )
            ) {
                SessionReconciliationResult
                    .MATCHED_ACTIVE_SESSION ->
                    ProtectionRecoveryResult
                        .ACTIVE_SESSION_MATCHED

                SessionReconciliationResult
                    .STALE_RECORD_CLEARED ->
                    ProtectionRecoveryResult
                        .STALE_SESSION_CLEARED

                SessionReconciliationResult.NO_RECORD ->
                    ProtectionRecoveryResult
                        .NO_SAVED_DATA

                SessionReconciliationResult
                    .WAITING_FOR_VERIFICATION ->
                    ProtectionRecoveryResult
                        .WAITING_FOR_VERIFICATION
            }
        }

        return when (
            orphanRecovery.recover(
                snapshot = snapshot,
                currentBootCount = currentBootCount
            )
        ) {
            OrphanUnlockRecoveryResult
                .NO_UNLOCK_REQUEST ->
                ProtectionRecoveryResult.NO_SAVED_DATA

            OrphanUnlockRecoveryResult
                .ACTIVE_SESSION_MATCHES ->
                ProtectionRecoveryResult
                    .ORPHAN_UNLOCK_PRESERVED

            OrphanUnlockRecoveryResult
                .ORPHAN_UNLOCK_CLEARED ->
                ProtectionRecoveryResult
                    .ORPHAN_UNLOCK_CLEARED

            OrphanUnlockRecoveryResult
                .WAITING_FOR_VERIFICATION ->
                ProtectionRecoveryResult
                    .WAITING_FOR_VERIFICATION

            OrphanUnlockRecoveryResult
                .SESSION_RECORD_PRESENT ->
                // Se outro componente inseriu um registro
                // durante a operação, não fazemos limpeza.
                ProtectionRecoveryResult
                    .WAITING_FOR_VERIFICATION
        }
    }
}
