package br.com.reboot.poc.application.protection

sealed interface VpnOperationalSnapshot {

    // Ainda não houve verificação conclusiva.
    data object Unknown : VpnOperationalSnapshot

    // O serviço confirmou ausência de VPN ativa.
    data object Inactive : VpnOperationalSnapshot

    // O serviço confirmou uma sessão ativa.
    data class Active(
        val sessionKey: String
    ) : VpnOperationalSnapshot
}
