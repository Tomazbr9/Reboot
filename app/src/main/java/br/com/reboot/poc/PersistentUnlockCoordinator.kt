package br.com.reboot.poc

import java.util.UUID

data class PersistentUnlockStatus(
    val requestId: String,
    val protectionSessionKey: String,
    val delay: UnlockDelay,
    val state: UnlockRequestState,
    val remainingMillis: Long
)

class PersistentUnlockCoordinator(
    private val repository: PersistentUnlockRepository,
    private val elapsedRealtimeMillis: () -> Long,
    private val bootCount: () -> Int
) {

    @Synchronized
    fun requestUnlock(
        protectionSessionKey: String,
        delay: UnlockDelay
    ): PersistentUnlockStatus {
        requireValidSessionKey(protectionSessionKey)


        val existing = restoreValidRequest()

        if (existing != null) {
            check(
                existing.protectionSessionKey ==
                        protectionSessionKey
            ) {
                "Pending unlock belongs to another VPN session"
            }

            // A solicitação duplicada não altera
            // o UUID, a duração ou o prazo original.
            return toStatus(existing)
        }

        // Uma nova sessão não reutiliza o pedido anterior.
        // Nesta PoC, chamadas são serializadas por esta
        // instância do coordenador.
        val request = StoredUnlockRequest.create(
            protectionSessionKey = protectionSessionKey,
            delay = delay,
            nowElapsedMillis = elapsedRealtimeMillis(),
            bootCount = bootCount()
        )

        repository.save(request)
        return toStatus(request)
    }

    @Synchronized
    fun getStatus(
        protectionSessionKey: String
    ): PersistentUnlockStatus? {
        val request = restoreValidRequest()
            ?: return null

        if (
            request.protectionSessionKey !=
            protectionSessionKey
        ) {
            return null
        }

        return toStatus(request)
    }

    @Synchronized
    fun cancel(
        protectionSessionKey: String,
        requestId: String
    ): Boolean {
        val request = restoreValidRequest()
            ?: return false

        if (
            request.protectionSessionKey !=
            protectionSessionKey ||
            request.requestId != requestId
        ) {
            return false
        }

        repository.clear()
        return true
    }

    /**
     * Verificação somente de leitura.
     *
     * NÃO confirma definitivamente o pedido
     * nem encerra a VPN.
     */
    @Synchronized
    fun canConfirm(
        protectionSessionKey: String,
        requestId: String
    ): Boolean {
        val request = restoreValidRequest()
            ?: return false

        return request.protectionSessionKey ==
                protectionSessionKey &&
                request.requestId == requestId &&
                request.isReady(elapsedRealtimeMillis())
    }

    private fun restoreValidRequest():
            StoredUnlockRequest? {

        val request = repository.load()
            ?: return null

        // elapsedRealtime reinicia após reboot.
        // Não reutilizar um prazo de outro boot.
        if (request.bootCount != bootCount()) {
            repository.clear()
            return null
        }

        return request
    }

    private fun toStatus(
        request: StoredUnlockRequest
    ): PersistentUnlockStatus {

        val remaining = request.remainingMillis(
            elapsedRealtimeMillis()
        )

        return PersistentUnlockStatus(
            requestId = request.requestId,
            protectionSessionKey =
                request.protectionSessionKey,
            delay = request.delay,
            state = if (remaining == 0L) {
                UnlockRequestState.READY
            } else {
                UnlockRequestState.WAITING
            },
            remainingMillis = remaining
        )
    }

    private fun requireValidSessionKey(value: String) {
        require(
            runCatching {
                UUID.fromString(value).toString() == value
            }.getOrDefault(false)
        ) {
            "Invalid protection session key"
        }
    }
}
