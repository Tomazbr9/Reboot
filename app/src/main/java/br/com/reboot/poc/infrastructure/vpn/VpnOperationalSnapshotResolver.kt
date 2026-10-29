
package br.com.reboot.poc.infrastructure.vpn

import br.com.reboot.poc.application.protection.VpnOperationalSnapshot

/**
 * Converte o estado conhecido pelo serviço VPN
 * em um snapshot para a aplicação.
 *
 * Não consulta SharedPreferences.
 * Não inicia nem encerra a VPN.
 */
class VpnOperationalSnapshotResolver(
    private val sessionGuard: VpnSessionGuard
) {

    fun resolve(
        session: VpnSessionResources?
    ): VpnOperationalSnapshot {

        if (session == null) {
            return VpnOperationalSnapshot.Unknown
        }

        if (session.isStopped()) {
            return VpnOperationalSnapshot.Unknown
        }

        if (!sessionGuard.isActive(session.sessionId)) {
            return VpnOperationalSnapshot.Unknown
        }

        return VpnOperationalSnapshot.Active(
            sessionKey = session.sessionKey
        )
    }
}
