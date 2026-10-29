package br.com.reboot.poc.application.ports

import br.com.reboot.poc.domain.unlock.StoredUnlockRequest

interface PersistentUnlockRepository {
    fun save(request: StoredUnlockRequest)
    fun load(): StoredUnlockRequest?
    fun clear()
}
