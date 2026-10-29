package br.com.reboot.poc

import br.com.reboot.poc.infrastructure.dns.DnsTaskExecutor
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DnsTaskExecutorTest {

    @Test
    fun executesSubmittedTask() {
        val executor = DnsTaskExecutor(
            workers = 1,
            queueCapacity = 2
        )

        val finished = CountDownLatch(1)

        try {

            assertTrue(
                executor.submit {
                    finished.countDown()
                }
            )

            assertTrue(
                finished.await(2, TimeUnit.SECONDS)
            )
        } finally {
            executor.close()
        }
    }

    @Test
    fun runsTwoWorkersConcurrently() {
        val executor = DnsTaskExecutor(
            workers = 2,
            queueCapacity = 2
        )

        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)

        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        try {
            repeat(2) {
                assertTrue(
                    executor.submit {
                        val current =
                            active.incrementAndGet()

                        maxActive.updateAndGet { old ->
                            maxOf(old, current)
                        }

                        started.countDown()

                        try {
                            release.await(
                                2,
                                TimeUnit.SECONDS
                            )
                        } finally {
                            active.decrementAndGet()
                            finished.countDown()
                        }
                    }
                )
            }

            assertTrue(
                started.await(2, TimeUnit.SECONDS)
            )

            assertEquals(2, maxActive.get())

        } finally {
            release.countDown()
            finished.await(2, TimeUnit.SECONDS)
            executor.close()
        }
    }

    @Test
    fun rejectsWhenQueueIsFull() {
        val executor = DnsTaskExecutor(
            workers = 1,
            queueCapacity = 1
        )

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        try {
            // Primeiro trabalho ocupa o worker.
            assertTrue(
                executor.submit {
                    started.countDown()
                    release.await(
                        2,
                        TimeUnit.SECONDS
                    )
                }
            )

            assertTrue(
                started.await(2, TimeUnit.SECONDS)
            )

            // Segundo trabalho ocupa a fila.
            assertTrue(executor.submit {})

            // Terceiro ultrapassa a capacidade.
            assertFalse(executor.submit {})

        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun rejectsNewTasksAfterClose() {
        val executor = DnsTaskExecutor(
            workers = 1,
            queueCapacity = 2
        )

        executor.close()

        assertTrue(executor.isClosed())
        assertFalse(executor.submit {})
    }

    @Test
    fun discardsQueuedTaskOnClose() {
        val executor = DnsTaskExecutor(
            workers = 1,
            queueCapacity = 1
        )

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedExecuted = AtomicBoolean(false)

        try {
            assertTrue(
                executor.submit {
                    started.countDown()

                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            )

            assertTrue(
                started.await(2, TimeUnit.SECONDS)
            )

            assertTrue(
                executor.submit {
                    queuedExecuted.set(true)
                }
            )

            executor.close()
            release.countDown()

            assertFalse(queuedExecuted.get())

        } finally {
            release.countDown()
            executor.close()
        }
    }
}
