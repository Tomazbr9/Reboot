package br.com.reboot.poc

enum class UnlockRequestState {
    WAITING,
    READY,
    CANCELLED,
    CONFIRMED
}

data class UnlockRequestSnapshot(
    val requestId: Long,
    val protectionSessionId: Long,
    val delay: UnlockDelay,
    val state: UnlockRequestState,
    val remainingMillis: Long
)

class UnlockRequestManager(
    private val clockMillis: () -> Long
) {
    private data class Request(
        val id: Long,
        val sessionId: Long,
        val deadline: UnlockDeadline,
        var state: UnlockRequestState
    )

    private var nextId = 0L
    private var current: Request? = null

    @Synchronized
    fun requestUnlock(
        sessionId: Long,
        delay: UnlockDelay
    ): UnlockRequestSnapshot {
        require(sessionId > 0L)

        val existing = current

        if (
            existing != null &&
            existing.state != UnlockRequestState.CANCELLED &&
            existing.state != UnlockRequestState.CONFIRMED
        ) {
            check(existing.sessionId == sessionId) {
                "Unlock request belongs to another session"
            }

            // Solicitações duplicadas não reiniciam
            // nem alteram o prazo.
            return snapshot(existing)
        }

        val deadline = UnlockDeadline.request(
            delay,
            clockMillis()
        )

        val newRequest = Request(
            id = ++nextId,
            sessionId = sessionId,
            deadline = deadline,
            state = UnlockRequestState.WAITING
        )

        current = newRequest

        return snapshot(newRequest)
    }

    @Synchronized
    fun getStatus(
        sessionId: Long
    ): UnlockRequestSnapshot? {
        val request = current ?: return null

        if (request.sessionId != sessionId) {
            return null
        }

        return snapshot(request)
    }

    @Synchronized
    fun cancel(
        sessionId: Long,
        requestId: Long
    ): Boolean {
        val request = current ?: return false

        if (
            request.sessionId != sessionId ||
            request.id != requestId ||
            request.state == UnlockRequestState.CANCELLED ||
            request.state == UnlockRequestState.CONFIRMED
        ) {
            return false
        }

        request.state = UnlockRequestState.CANCELLED
        return true
    }

    /**
     * Confirma a autorização de desligamento.
     *
     * NÃO encerra a VPN. O serviço Android
     * precisará validar a sessão novamente
     * e executar a parada separadamente.
     */
    @Synchronized
    fun confirm(
        sessionId: Long,
        requestId: Long
    ): Boolean {
        val request = current ?: return false

        if (
            request.sessionId != sessionId ||
            request.id != requestId ||
            request.state == UnlockRequestState.CANCELLED ||
            request.state == UnlockRequestState.CONFIRMED
        ) {
            return false
        }

        if (!request.deadline.isReady(clockMillis())) {
            return false
        }

        request.state = UnlockRequestState.CONFIRMED
        return true
    }

    private fun snapshot(
        request: Request
    ): UnlockRequestSnapshot {
        val state = when (request.state) {
            UnlockRequestState.WAITING,
            UnlockRequestState.READY -> {
                if (
                    request.deadline.isReady(
                        clockMillis()
                    )
                ) {
                    UnlockRequestState.READY
                } else {
                    UnlockRequestState.WAITING
                }
            }

            else -> request.state
        }

        val remaining = when (state) {
            UnlockRequestState.WAITING ->
                request.deadline.remainingMillis(
                    clockMillis()
                )

            else -> 0L
        }

        return UnlockRequestSnapshot(
            requestId = request.id,
            protectionSessionId = request.sessionId,
            delay = request.deadline.delay,
            state = state,
            remainingMillis = remaining
        )
    }
}
