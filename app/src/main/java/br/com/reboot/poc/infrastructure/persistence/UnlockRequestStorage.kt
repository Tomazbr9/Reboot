package br.com.reboot.poc.infrastructure.persistence

import android.content.Context
import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest

class UnlockRequestStorage(context: Context) : PersistentUnlockRepository {

    private val preferences =
        context.applicationContext.getSharedPreferences(
            "reboot_unlock",
            Context.MODE_PRIVATE
        )

    override
    fun save(request: StoredUnlockRequest) {
        val saved = preferences.edit()
            .putString(
                KEY_PENDING_REQUEST,
                UnlockRequestCodec.encode(request)
            )
            .commit()

        check(saved) {
            "Could not persist unlock request"
        }
    }

    override
    fun load(): StoredUnlockRequest? {
        val encoded = preferences.getString(
            KEY_PENDING_REQUEST,
            null
        ) ?: return null

        return UnlockRequestCodec.decode(encoded)
    }

    override
    fun clear() {
        val cleared = preferences.edit()
            .remove(KEY_PENDING_REQUEST)
            .commit()

        check(cleared) {
            "Could not clear unlock request"
        }
    }

    companion object {
        private const val KEY_PENDING_REQUEST =
            "pending_unlock_request"
    }
}
