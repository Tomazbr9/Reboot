package br.com.reboot.poc.infrastructure.vpn

enum class VpnState {
    INACTIVE,
    STARTING,
    ACTIVE,
    STOPPING
}

class VpnSessionGuard {

    private var state = VpnState.INACTIVE
    private var generation = 0L

    @Synchronized
    fun beginStart(): Long? {
        if (state != VpnState.INACTIVE) return null

        generation++
        state = VpnState.STARTING
        return generation
    }

    @Synchronized
    fun markActive(sessionId: Long): Boolean {
        if (sessionId != generation ||
            state != VpnState.STARTING
        ) return false

        state = VpnState.ACTIVE
        return true
    }

    @Synchronized
    fun isActive(sessionId: Long): Boolean {
        return state == VpnState.ACTIVE &&
                generation == sessionId
    }

    @Synchronized
    fun beginStop(): Long? {
        if (state != VpnState.ACTIVE &&
            state != VpnState.STARTING
        ) return null

        state = VpnState.STOPPING
        return generation
    }

    @Synchronized
    fun finishStop(sessionId: Long) {
        if (generation == sessionId &&
            state == VpnState.STOPPING
        ) {
            state = VpnState.INACTIVE
        }
    }

    @Synchronized
    fun currentState(): VpnState = state


    @Synchronized
    fun ensureStopping(sessionId: Long): Boolean {
        if (sessionId != generation) return false

        return when (state) {
            VpnState.STARTING,
            VpnState.ACTIVE -> {
                state = VpnState.STOPPING
                true
            }

            VpnState.STOPPING -> true

            VpnState.INACTIVE -> false
        }
    }

}
