package br.com.reboot.poc.domain.unlock

enum class UnlockDelay(val minutes: Long) {
    MINUTES_30(30),
    HOUR_1(60),
    HOURS_2(120),
    HOURS_4(240);

    val durationMillis: Long
        get() = minutes * 60_000L
}