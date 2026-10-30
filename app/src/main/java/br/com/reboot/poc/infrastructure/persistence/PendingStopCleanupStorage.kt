package br.com.reboot.poc.infrastructure.persistence

import android.content.Context
import br.com.reboot.poc.application.ports.PendingStopCleanupRepository
import br.com.reboot.poc.domain.protection.PendingStopCleanup

class PendingStopCleanupStorage(
    context: Context
) : PendingStopCleanupRepository {

    private val preferences =
        context.applicationContext.getSharedPreferences(
            "reboot_pending_stop_cleanup",
            Context.MODE_PRIVATE
        )

    override fun save(value: PendingStopCleanup) {
        check(
            preferences.edit()
                .putString(KEY_SESSION, value.sessionKey)
                .commit()
        ) {
            "Could not save pending stop cleanup"
        }
    }

    override fun load(): PendingStopCleanup? {
        val sessionKey = preferences.getString(
            KEY_SESSION,
            null
        ) ?: return null

        return PendingStopCleanup(sessionKey)
    }

    override fun clear() {
        check(
            preferences.edit()
                .remove(KEY_SESSION)
                .commit()
        ) {
            "Could not clear pending stop cleanup"
        }
    }

    companion object {
        private const val KEY_SESSION =
            "pending_stop_session_key"
    }
}
