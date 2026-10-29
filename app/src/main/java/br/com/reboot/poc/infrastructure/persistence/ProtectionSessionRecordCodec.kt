package br.com.reboot.poc.infrastructure.persistence

import br.com.reboot.poc.domain.protection.ProtectionSessionRecord

object ProtectionSessionRecordCodec {

    fun encode(
        record: ProtectionSessionRecord
    ): String = listOf(
        "v1",
        record.sessionKey,
        record.bootCount,
        record.activatedAtElapsedMillis
    ).joinToString("|")

    fun decode(
        value: String
    ): ProtectionSessionRecord? {
        val parts = value.split("|")

        if (parts.size != 4 || parts[0] != "v1") {
            return null
        }

        return try {
            ProtectionSessionRecord(
                sessionKey = parts[1],
                bootCount = parts[2].toInt(),
                activatedAtElapsedMillis =
                    parts[3].toLong()
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
