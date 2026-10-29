
package br.com.reboot.poc.application.protection

import br.com.reboot.poc.domain.protection.ProtectionSessionRecord

enum class ProtectionRecoveryDecision {
    NO_SAVED_SESSION,
    WAITING_FOR_VERIFICATION,
    MATCHED_ACTIVE_SESSION,
    DIFFERENT_ACTIVE_SESSION,
    CONFIRMED_INACTIVE,
    PREVIOUS_BOOT_SESSION
}

class ProtectionRecoveryEvaluator {

    fun evaluate(
        saved: ProtectionSessionRecord?,
        snapshot: VpnOperationalSnapshot,
        currentBootCount: Int
    ): ProtectionRecoveryDecision {
        require(currentBootCount >= 0)

        if (saved == null) {
            return ProtectionRecoveryDecision.NO_SAVED_SESSION
        }

        // Enquanto o estado operacional é desconhecido,
        // não concluímos que a sessão antiga terminou.
        if (snapshot == VpnOperationalSnapshot.Unknown) {
            return ProtectionRecoveryDecision
                .WAITING_FOR_VERIFICATION
        }

        if (saved.bootCount != currentBootCount) {
            return ProtectionRecoveryDecision
                .PREVIOUS_BOOT_SESSION
        }

        return when (snapshot) {
            is VpnOperationalSnapshot.Active -> {
                if (snapshot.sessionKey == saved.sessionKey) {
                    ProtectionRecoveryDecision
                        .MATCHED_ACTIVE_SESSION
                } else {
                    ProtectionRecoveryDecision
                        .DIFFERENT_ACTIVE_SESSION
                }
            }

            VpnOperationalSnapshot.Inactive ->
                ProtectionRecoveryDecision.CONFIRMED_INACTIVE

            VpnOperationalSnapshot.Unknown ->
                ProtectionRecoveryDecision
                    .WAITING_FOR_VERIFICATION
        }
    }
}
