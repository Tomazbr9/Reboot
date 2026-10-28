package br.com.reboot.poc

import java.util.UUID

/**
 * Pedido de desbloqueio que pode ser
 * armazenado no dispositivo.
 *
 * O tempo é medido desde a solicitação,
 * não desde a ativação da VPN.
 */
data class StoredUnlockRequest(
    val requestId: String,
    val protectionSessionKey: String,
    val delay: UnlockDelay,
    val requestedAtElapsedMillis: Long,
    val bootCount: Int
) {
    init {
        require(requestedAtElapsedMillis >= 0L)
        require(bootCount >= 0)
        requireValidUuid(requestId)
        requireValidUuid(protectionSessionKey)

        Math.addExact(
            requestedAtElapsedMillis,
            delay.durationMillis
        )
    }

    fun remainingMillis(nowElapsedMillis: Long): Long {
        require(nowElapsedMillis >= 0L)

        return (
                requestedAtElapsedMillis +
                        delay.durationMillis -
                        nowElapsedMillis
                ).coerceAtLeast(0L)
    }

    fun isReady(nowElapsedMillis: Long): Boolean =
        remainingMillis(nowElapsedMillis) == 0L

    companion object {
        fun create(
            protectionSessionKey: String,
            delay: UnlockDelay,
            nowElapsedMillis: Long,
            bootCount: Int
        ): StoredUnlockRequest {
            return StoredUnlockRequest(
                requestId = UUID.randomUUID().toString(),
                protectionSessionKey = protectionSessionKey,
                delay = delay,
                requestedAtElapsedMillis = nowElapsedMillis,
                bootCount = bootCount
            )
        }

        private fun requireValidUuid(value: String) {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) {
                "Invalid UUID"
            }
        }
    }
}
