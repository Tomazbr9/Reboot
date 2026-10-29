package br.com.reboot.poc.infrastructure.persistence

import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay

object UnlockRequestCodec {

    private const val VERSION = "v1"

    fun encode(request: StoredUnlockRequest): String =
        listOf(
            VERSION,
            request.requestId,
            request.protectionSessionKey,
            request.delay.name,
            request.requestedAtElapsedMillis,
            request.bootCount
        ).joinToString("|")

    fun decode(value: String): StoredUnlockRequest? {
        val parts = value.split("|")

        if (parts.size != 6) return null
        if (parts[0] != VERSION) return null

        return try {
            StoredUnlockRequest(
                requestId = parts[1],
                protectionSessionKey = parts[2],
                delay = UnlockDelay.valueOf(parts[3]),
                requestedAtElapsedMillis =
                    parts[4].toLong(),
                bootCount = parts[5].toInt()
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }
}
