package br.com.reboot.poc.domain.protection

import java.util.UUID

data class VpnSessionIdentity(
    val localId: Long,
    val sessionKey: String
) {
    init {
        require(localId > 0L) {
            "Invalid local session ID"
        }

        val parsed = UUID.fromString(sessionKey)

        require(parsed.toString() == sessionKey) {
            "Invalid session UUID"
        }
    }

    companion object {
        fun create(localId: Long): VpnSessionIdentity {
            return VpnSessionIdentity(
                localId = localId,
                sessionKey = UUID.randomUUID().toString()
            )
        }
    }
}
