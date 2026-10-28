package br.com.reboot.poc

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Associa os recursos de uma execução da VPN
 * a um identificador de sessão.
 *
 * A classe não depende do Android, permitindo
 * testes unitários na JVM.
 */
class VpnSessionResources(
    val sessionId: Long,
    val identity: VpnSessionIdentity = VpnSessionIdentity.create(sessionId)
) : AutoCloseable {

    init {
        require(identity.localId == sessionId) {
            "Session identity mismatch"
        }
    }

    val sessionKey: String
        get() = identity.sessionKey

    private val scope = VpnResourceScope()
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var reader: Thread? = null

    fun <T : AutoCloseable> register(resource: T): T {
        return scope.register(resource)
    }

    fun setReader(thread: Thread) {
        check(!stopped.get()) {
            "VPN session already stopped"
        }

        synchronized(this) {
            check(!stopped.get()) {
                "VPN session already stopped"
            }
            check(reader == null) {
                "VPN reader already assigned"
            }
            reader = thread
        }
    }

    fun isStopped(): Boolean = stopped.get()

    override fun close() {
        val threadToInterrupt: Thread?

        synchronized(this) {
            if (!stopped.compareAndSet(false, true)) {
                return
            }
            threadToInterrupt = reader
        }

        var closeError: Exception? = null

        try {
            // O scope encerra primeiro os workers DNS,
            // depois o túnel, conforme a ordem de registro.
            scope.close()
        } catch (ex: Exception) {
            closeError = ex
        } finally {
            if (threadToInterrupt !== Thread.currentThread()) {
                threadToInterrupt?.interrupt()
            }
        }

        if (closeError != null) throw closeError
    }


    fun awaitReaderTermination(
        timeoutMillis: Long = 1000L
    ): Boolean {
        require(timeoutMillis >= 0L)

        val thread = synchronized(this) {
            reader
        } ?: return true

        if (thread === Thread.currentThread()) {
            return false
        }

        return try {
            thread.join(timeoutMillis)
            !thread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }



}
