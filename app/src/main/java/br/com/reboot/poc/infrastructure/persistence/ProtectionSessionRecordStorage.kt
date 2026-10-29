package br.com.reboot.poc.infrastructure.persistence

import android.content.Context
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord

class ProtectionSessionRecordStorage(
    context: Context
) : ProtectionSessionRecordRepository {

    private val preferences =
        context.applicationContext.getSharedPreferences(
            "reboot_protection_session",
            Context.MODE_PRIVATE
        )

    override fun save(record: ProtectionSessionRecord) {
        check(
            preferences.edit()
                .putString(
                    KEY,
                    ProtectionSessionRecordCodec.encode(
                        record
                    )
                )
                .commit()
        ) {
            "Failed to save protection session"
        }
    }

    override fun load(): ProtectionSessionRecord? {
        val value = preferences.getString(
            KEY,
            null
        ) ?: return null

        return ProtectionSessionRecordCodec.decode(
            value
        )
    }

    override fun clear() {
        check(
            preferences.edit()
                .remove(KEY)
                .commit()
        ) {
            "Failed to clear protection session"
        }
    }

    private companion object {
        const val KEY = "current_protection_session"
    }
}
