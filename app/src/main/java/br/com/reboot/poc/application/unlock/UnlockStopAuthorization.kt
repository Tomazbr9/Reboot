package br.com.reboot.poc.application.unlock

import br.com.reboot.poc.application.ports.PersistentUnlockRepository

enum class UnlockStopDecision {
    AUTHORIZED,
    VPN_NOT_ACTIVE,
    REQUEST_NOT_FOUND,
    SESSION_MISMATCH,
    REQUEST_MISMATCH,
    PREVIOUS_BOOT,
    WAITING
}

/**
 * Decide se uma confirmação voluntária
 * pode autorizar o encerramento da VPN.
 *
 * Não encerra a VPN nem altera o pedido.
 */
class UnlockStopAuthorization(
    private val repository: PersistentUnlockRepository,
    private val elapsedRealtimeMillis: () -> Long,
    private val bootCount: () -> Int
) {

    @Synchronized
    fun evaluate(
        activeSessionKey: String,
        requestId: String,
        vpnIsActive: Boolean
    ): UnlockStopDecision {

        if (!vpnIsActive) {
            return UnlockStopDecision.VPN_NOT_ACTIVE
        }

        val request = repository.load()
            ?: return UnlockStopDecision.REQUEST_NOT_FOUND

        if (request.bootCount != bootCount()) {
            return UnlockStopDecision.PREVIOUS_BOOT
        }

        if (
            request.protectionSessionKey !=
            activeSessionKey
        ) {
            return UnlockStopDecision.SESSION_MISMATCH
        }

        if (request.requestId != requestId) {
            return UnlockStopDecision.REQUEST_MISMATCH
        }

        if (!request.isReady(elapsedRealtimeMillis())) {
            return UnlockStopDecision.WAITING
        }

        return UnlockStopDecision.AUTHORIZED
    }
}
