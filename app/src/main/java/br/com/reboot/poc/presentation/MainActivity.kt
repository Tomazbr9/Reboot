package br.com.reboot.poc.presentation

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import br.com.reboot.poc.infrastructure.vpn.RebootDnsVpnService

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val vpnRequestCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "PoC de laboratório: TUN sem encaminhamento DNS. Pode interromper a resolução DNS enquanto estiver ativo."
            textSize = 17f
        }
        val start = Button(this).apply {
            text = "Autorizar e ativar VPN de teste"
            setOnClickListener { requestVpn() }
        }
        val stop = Button(this).apply {
            text = "Desativar VPN de teste"
            setOnClickListener { sendCommand(RebootDnsVpnService.ACTION_STOP) }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            addView(status)
            addView(start)
            addView(stop)
        }
        setContentView(panel)
    }

    private fun requestVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, vpnRequestCode)
        } else {
            sendCommand(RebootDnsVpnService.ACTION_START)
        }
    }

    @Deprecated("Uses Activity callback for a dependency-free PoC")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            if (resultCode == RESULT_OK) sendCommand(RebootDnsVpnService.ACTION_START)
            else status.text = "Autorização recusada; VPN não iniciada."
        }
    }

    private fun sendCommand(action: String) {
        val intent = Intent(this, RebootDnsVpnService::class.java).setAction(action)
        if (action == RebootDnsVpnService.ACTION_START) startForegroundService(intent)
        else startService(intent)
        status.text = if (action == RebootDnsVpnService.ACTION_START)
            "Solicitação enviada. Confira a notificação e os logs; isso não confirma DNS funcional."
        else "Parada solicitada."
    }
}
