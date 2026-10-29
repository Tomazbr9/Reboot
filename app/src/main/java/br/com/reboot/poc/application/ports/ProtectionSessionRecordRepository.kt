package br.com.reboot.poc.application.ports

import br.com.reboot.poc.domain.protection.ProtectionSessionRecord

interface ProtectionSessionRecordRepository {
    fun save(record: ProtectionSessionRecord)
    fun load(): ProtectionSessionRecord?
    fun clear()
}
