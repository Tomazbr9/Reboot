
package br.com.reboot.poc

data class DnsQuery(
    val payload: ByteArray,
    val sourcePort: Int,
    val transactionId: Int
)

object DnsQueryExtractor {

    fun extract(
        packet: ByteArray,
        length: Int
    ): DnsQuery? {

        if (
            DnsPacketClassifier.classify(
                packet, length
            ) != PacketKind.DNS_UDP_53
        ) return null

        val ipHeaderLength =
            (packet[0].toInt() and 0x0F) * 4

        fun u16(offset: Int): Int =
            ((packet[offset].toInt() and 0xFF) shl 8) or
                    (packet[offset + 1].toInt() and 0xFF)

        val ipTotalLength = u16(2)

        val sourcePort = u16(ipHeaderLength)
        val udpLength = u16(ipHeaderLength + 4)

        val dnsStart = ipHeaderLength + 8
        val dnsEnd = ipHeaderLength + udpLength

        if (
            ipTotalLength > length ||
            dnsEnd > ipTotalLength ||
            dnsEnd - dnsStart < 12
        ) return null

        val payload = packet.copyOfRange(
            dnsStart,
            dnsEnd
        )

        val transactionId =
            ((payload[0].toInt() and 0xFF) shl 8) or
                    (payload[1].toInt() and 0xFF)

        // DNS QR bit: 0 = consulta.
        val isResponse =
            (payload[2].toInt() and 0x80) != 0

        if (isResponse) return null

        return DnsQuery(
            payload = payload,
            sourcePort = sourcePort,
            transactionId = transactionId
        )
    }
}
