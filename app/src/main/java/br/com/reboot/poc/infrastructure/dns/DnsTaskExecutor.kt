package br.com.reboot.poc.infrastructure.dns

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DnsTaskExecutor(
    workers: Int = 4,
    queueCapacity: Int = 32
) {
    init {
        require(workers > 0)
        require(queueCapacity > 0)
    }

    private val closed = AtomicBoolean(false)

    private val executor = ThreadPoolExecutor(
        workers,
        workers,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        { task ->
            Thread(task, "dns-relay-worker").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    /**
     * Retorna false caso o executor esteja fechado
     * ou tenha atingido sua capacidade.
     */
    fun submit(task: () -> Unit): Boolean {
        if (closed.get()) return false

        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /**
     * Interrompe workers e descarta tarefas pendentes.
     * Não garante cancelamento imediato de I/O bloqueante.
     */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
        }
    }

    fun isClosed(): Boolean = closed.get()
}
