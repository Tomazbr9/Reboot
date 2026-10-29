package br.com.reboot.poc.domain.unlock

class UnlockDeadline(
    val delay: UnlockDelay,
    val requestedAtMillis: Long
) {
    val availableAtMillis: Long = Math.addExact(
        requestedAtMillis,
        delay.durationMillis
    )

    fun isReady(nowMillis: Long): Boolean =
        nowMillis >= availableAtMillis

    fun remainingMillis(nowMillis: Long): Long =
        if (isReady(nowMillis)) {
            0L
        } else {
            availableAtMillis - nowMillis
        }

    companion object {
        fun request(
            delay: UnlockDelay,
            nowMillis: Long
        ): UnlockDeadline {
            require(nowMillis >= 0L)
            return UnlockDeadline(delay, nowMillis)
        }
    }
}