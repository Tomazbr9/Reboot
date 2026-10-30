package br.com.reboot.poc.presentation

/**
 * Controla a ordem das respostas recebidas
 * pela interface.
 *
 * Não depende do Android.
 */
class UiResponseTracker {

    private var lastIssued = 0L

    @Synchronized
    fun issue(): Long {
        lastIssued++
        return lastIssued
    }

    @Synchronized
    fun shouldAccept(responseId: Long): Boolean {
        return responseId == lastIssued
    }
}
