package br.com.reboot.poc

import java.util.UUID

data class ProtectionSessionRecord(
    val sessionKey: String,
    val bootCount: Int,
    val activatedAtElapsedMillis: Long
) {
    init {
        require(bootCount >= 0)
        require(activatedAtElapsedMillis >= 0L)

        require(
            UUID.fromString(sessionKey).toString() ==
                    sessionKey
        ) {
            "Invalid protection session UUID"
        }
    }
}
