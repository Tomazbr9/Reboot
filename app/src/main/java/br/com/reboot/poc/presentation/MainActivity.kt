package br.com.reboot.poc.presentation

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import br.com.reboot.poc.domain.unlock.UnlockDelay
import br.com.reboot.poc.infrastructure.vpn.RebootDnsVpnService as Vpn
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var countdown: TextView
    private lateinit var requestButton: Button
    private lateinit var cancelButton: Button
    private lateinit var confirmButton: Button
    private lateinit var delaySelector: Spinner

    private val responseTracker = UiResponseTracker()
    private val handler = Handler(Looper.getMainLooper())
    private val vpnRequestCode = 42

    private var requestId: String? = null
    private var currentState = "UNKNOWN"

    private val delays = listOf(
        UnlockDelay.MINUTES_30,
        UnlockDelay.HOUR_1,
        UnlockDelay.HOURS_2,
        UnlockDelay.HOURS_4
    )

    private fun createResultReceiver(
        commandId: Long
    ): ResultReceiver {

        return object : ResultReceiver(handler) {

            override fun onReceiveResult(
                resultCode: Int,
                resultData: Bundle?
            ) {
                if (resultCode != Vpn.RESULT_STATUS) {
                    return
                }

                // Ignora uma resposta que chegou depois
                // de outro comando mais recente.
                if (!responseTracker.shouldAccept(commandId)) {
                    return
                }

                val state = resultData?.getString(
                    Vpn.KEY_STATE
                ) ?: "ERROR"

                val id = resultData?.getString(
                    Vpn.KEY_REQUEST_ID
                )

                val remaining = resultData?.getLong(
                    Vpn.KEY_REMAINING_MILLIS,
                    0L
                ) ?: 0L

                val message = resultData?.getString(
                    Vpn.KEY_MESSAGE
                ).orEmpty()

                renderState(
                    state = state,
                    id = id,
                    remaining = remaining,
                    message = message
                )
            }
        }
    }

    private val statusPoll = object : Runnable {
        override fun run() {

            if (
                currentState == "UNKNOWN" ||
                currentState == "WAITING" ||
                currentState == "READY"
            ) {
                sendCommand(
                    Vpn.ACTION_GET_UNLOCK_STATUS
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Consultando proteção..."
            textSize = 18f
        }

        countdown = TextView(this).apply {
            text = ""
            textSize = 26f
        }

        delaySelector = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "30 minutos",
                    "1 hora",
                    "2 horas",
                    "4 horas"
                )
            )
        }

        val startButton = Button(this).apply {
            text = "Ativar proteção"
            setOnClickListener { requestVpn() }
        }

        requestButton = Button(this).apply {
            text = "Solicitar desativação"
            setOnClickListener {
                sendCommand(
                    Vpn.ACTION_REQUEST_UNLOCK,
                    selectedDelay = delays[
                        delaySelector.selectedItemPosition
                    ]
                )
            }
        }

        cancelButton = Button(this).apply {
            text = "Cancelar solicitação"
            setOnClickListener {
                requestId?.let { id ->
                    sendCommand(
                        Vpn.ACTION_CANCEL_UNLOCK,
                        id = id
                    )
                }
            }
        }

        confirmButton = Button(this).apply {
            text = "Confirmar desativação"
            setOnClickListener {
                val id = requestId
                    ?: return@setOnClickListener

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Desativar proteção?")
                    .setMessage(
                        "A VPN será encerrada. " +
                                "Deseja realmente continuar?"
                    )
                    .setNegativeButton("Continuar protegido", null)
                    .setPositiveButton("Desativar") { _, _ ->
                        sendCommand(
                            Vpn.ACTION_CONFIRM_UNLOCK,
                            id = id
                        )
                    }
                    .show()
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)

            addView(status)
            addView(countdown)
            addView(startButton)
            addView(delaySelector)
            addView(requestButton)
            addView(cancelButton)
            addView(confirmButton)
        }

        setContentView(panel)

        renderState("UNKNOWN", null, 0L, "")
    }

    override fun onResume() {
        super.onResume()

        renderState(
            state = "UNKNOWN",
            id = null,
            remaining = 0L,
            message = ""
        )

        sendCommand(Vpn.ACTION_GET_UNLOCK_STATUS)
    }

    override fun onPause() {
        handler.removeCallbacks(statusPoll)
        super.onPause()
    }

    private fun renderState(
        state: String,
        id: String?,
        remaining: Long,
        message: String
    ) {
        currentState = state
        requestId = id

        handler.removeCallbacks(statusPoll)

        status.text = when (state) {
            "INACTIVE" -> "Proteção desativada"
            "PROTECTED" -> "Proteção ativa"
            "WAITING" -> "Aguardando liberação"
            "READY" -> "Desativação disponível"
            "ERROR" -> "Erro: $message"
            else -> "Verificando proteção..."
        }

        countdown.text = when (state) {
            "WAITING" -> formatRemaining(remaining)
            "READY" -> "Prazo concluído"
            else -> ""
        }

        requestButton.isEnabled = state == "PROTECTED"
        delaySelector.isEnabled = state == "PROTECTED"

        cancelButton.isEnabled =
            state == "WAITING" || state == "READY"

        confirmButton.isEnabled = state == "READY"

        if (
            state == "UNKNOWN" ||
            state == "WAITING" ||
            state == "READY"
        ) {
            handler.postDelayed(statusPoll, 1000L)
        }
    }

    private fun formatRemaining(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 999L) /
                1000L

        val hours = TimeUnit.SECONDS.toHours(seconds)
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L

        return "%02d:%02d:%02d".format(
            hours,
            minutes,
            secs
        )
    }

    private fun sendCommand(
        action: String,
        selectedDelay: UnlockDelay? = null,
        id: String? = null
    ) {

        val commandId = responseTracker.issue()

        val intent = Intent(this, Vpn::class.java).apply {
            this.action = action

            putExtra(
                Vpn.EXTRA_RESULT_RECEIVER,
                createResultReceiver(commandId)
            )

            selectedDelay?.let {
                putExtra(Vpn.EXTRA_UNLOCK_DELAY, it.name)
            }

            id?.let {
                putExtra(Vpn.EXTRA_REQUEST_ID, it)
            }
        }

        if (action == Vpn.ACTION_START) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestVpn() {
        val consent = VpnService.prepare(this)

        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                consent,
                vpnRequestCode
            )
        } else {
            sendCommand(Vpn.ACTION_START)
            renderState("UNKNOWN", null, 0L, "")
        }
    }

    @Deprecated("Laboratory VPN permission callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != vpnRequestCode) return

        if (resultCode == RESULT_OK) {
            sendCommand(Vpn.ACTION_START)
            renderState("UNKNOWN", null, 0L, "")
        } else {
            status.text = "Autorização de VPN recusada."
        }
    }
}
