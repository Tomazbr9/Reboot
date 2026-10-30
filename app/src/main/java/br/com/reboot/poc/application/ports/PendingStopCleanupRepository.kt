package br.com.reboot.poc.application.ports

import br.com.reboot.poc.domain.protection.PendingStopCleanup

interface PendingStopCleanupRepository {
    fun load(): PendingStopCleanup?
    fun save(value: PendingStopCleanup)
    fun clear()
}
