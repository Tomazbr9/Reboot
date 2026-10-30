
package br.com.reboot.poc.infrastructure.vpn

enum class VpnStopResult {
    STOPPED,
    STOPPED_WITH_ERRORS,
    STALE_SESSION,
    INVALID_STATE
}

/**
 * Classifica o encerramento após as tentativas
 * de limpeza de recursos e persistência.
 */
object VpnStopResultResolver {

    fun resolve(
        resourceCleanupSucceeded: Boolean,
        persistenceCleanupSucceeded: Boolean,
        foregroundCleanupSucceeded: Boolean
    ): VpnStopResult {
        return if (
            resourceCleanupSucceeded &&
            persistenceCleanupSucceeded &&
            foregroundCleanupSucceeded
        ) {
            VpnStopResult.STOPPED
        } else {
            VpnStopResult.STOPPED_WITH_ERRORS
        }
    }
}
