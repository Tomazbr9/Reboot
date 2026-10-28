package br.com.reboot.poc

import android.net.VpnService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class DnsUpstreamForwarder(
    private val vpnService: VpnService
) {

    private val socketRegistry = DnsSocketRegistry()

    fun close() {
        socketRegistry.close()
    }
    companion object {
        private const val DNS_SERVER = "1.1.1.3"
        private const val DNS_PORT = 53
        private const val TIMEOUT_MS = 1500
    }

    fun forward(query: DnsQuery): ByteArray {
        val address = InetAddress.getByName(DNS_SERVER)

        DatagramSocket(null).use { socket ->

            socketRegistry.register(socket)
            try {
                check(vpnService.protect(socket)) {
                    "Falha ao proteger socket DNS"
                }

                socket.bind(InetSocketAddress(0))
                socket.soTimeout = TIMEOUT_MS

                val request = DatagramPacket(
                    query.payload,
                    query.payload.size,
                    address,
                    DNS_PORT
                )

                socket.send(request)

                val responseBuffer = ByteArray(4096)

                val response = DatagramPacket(
                    responseBuffer,
                    responseBuffer.size
                )

                socket.receive(response)

                check(
                    response.address == address &&
                            response.port == DNS_PORT
                ) {
                    "Resposta recebida de origem inesperada"
                }

                val bytes = response.data.copyOfRange(
                    response.offset,
                    response.offset + response.length
                )

                check(bytes.size >= 12) {
                    "Resposta DNS incompleta"
                }

                val receivedId =
                    ((bytes[0].toInt() and 0xFF) shl 8) or
                            (bytes[1].toInt() and 0xFF)

                check(receivedId == query.transactionId) {
                    "ID DNS não corresponde à consulta"
                }

                check((bytes[2].toInt() and 0x80) != 0) {
                    "Pacote recebido não é resposta DNS"
                }


                val rcode = bytes[3].toInt() and 0x0F

                android.util.Log.i(
                    "RebootDnsPoc",
                    "dns_response_received " +
                            "rcode=$rcode " +
                            "bytes=${bytes.size}"
                )


                val inspection = DnsResponseInspector.inspect(bytes)

                if (inspection != null) {
                    android.util.Log.i(
                        "RebootDnsPoc",
                        "dns_inspection " +
                                "rcode=${inspection.responseCode} " +
                                "ipv4Answers=${inspection.ipv4Answers} " +
                                "hasZeroIpv4=${inspection.hasZeroIpv4}"
                    )
                }

                return bytes
            } finally {
                socketRegistry.unregister(socket)
            }
        }
    }
}
