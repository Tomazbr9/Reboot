package br.com.reboot.poc

interface PersistentUnlockRepository {
    fun save(request: StoredUnlockRequest)
    fun load(): StoredUnlockRequest?
    fun clear()
}
