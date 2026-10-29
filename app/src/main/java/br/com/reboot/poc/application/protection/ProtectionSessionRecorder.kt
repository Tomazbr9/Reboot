
package br.com.reboot.poc.application.protection

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.ports.ProtectionSessionRecordRepository
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord

class ProtectionSessionRecorder(
    private val sessions: ProtectionSessionRecordRepository,
    private val unlocks: PersistentUnlockRepository
) {

    @Synchronized
    fun onActivated(record: ProtectionSessionRecord) {
        val previous = sessions.load()

        if (
            previous != null &&
            previous.sessionKey != record.sessionKey
        ) {
            clearUnlockFor(previous.sessionKey)
        }

        sessions.save(record)
    }

    @Synchronized
    fun onStopped(sessionKey: String): Boolean {
        val saved = sessions.load() ?: return false

        // Uma parada antiga não pode apagar
        // a sessão que foi iniciada depois.
        if (saved.sessionKey != sessionKey) {
            return false
        }

        clearUnlockFor(sessionKey)
        sessions.clear()

        return true
    }

    private fun clearUnlockFor(sessionKey: String) {
        val request = unlocks.load()

        if (request?.protectionSessionKey == sessionKey) {
            unlocks.clear()
        }
    }
}
