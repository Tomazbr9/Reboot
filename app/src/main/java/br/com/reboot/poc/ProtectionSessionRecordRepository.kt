package br.com.reboot.poc

interface ProtectionSessionRecordRepository {
    fun save(record: ProtectionSessionRecord)
    fun load(): ProtectionSessionRecord?
    fun clear()
}
