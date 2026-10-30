
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

    /**
     * API existente, mantida para compatibilidade
     * com os outros componentes e testes.
     */
    @Synchronized
    fun onStopped(sessionKey: String): Boolean {
        return onStoppedDetailed(sessionKey) ==
                ProtectionStopCleanupResult.CLEANED
    }

    /**
     * Limpeza idempotente dos dados persistidos
     * associados à sessão encerrada.
     *
     * Não apaga registros de uma sessão diferente.
     *
     * Falhas de persistência são propagadas para
     * permitir diagnóstico e nova tentativa.
     */
    @Synchronized
    fun onStoppedDetailed(
        sessionKey: String
    ): ProtectionStopCleanupResult {

        val savedSession = sessions.load()
        val savedUnlock = unlocks.load()

        // Um registro de outra sessão não deve
        // ser alterado por esta operação.
        if (
            savedSession != null &&
            savedSession.sessionKey != sessionKey
        ) {
            return ProtectionStopCleanupResult.SESSION_MISMATCH
        }

        var removedSomething = false

        // Remove primeiro o pedido pertencente
        // à sessão encerrada.
        if (
            savedUnlock?.protectionSessionKey ==
            sessionKey
        ) {
            unlocks.clear()
            removedSomething = true
        }

        // Remove depois o registro da sessão.
        if (savedSession != null) {
            sessions.clear()
            removedSomething = true
        }

        return if (removedSomething) {
            ProtectionStopCleanupResult.CLEANED
        } else {
            ProtectionStopCleanupResult.ALREADY_CLEAN
        }
    }

    private fun clearUnlockFor(sessionKey: String) {
        val request = unlocks.load()

        if (request?.protectionSessionKey == sessionKey) {
            unlocks.clear()
        }
    }
}
