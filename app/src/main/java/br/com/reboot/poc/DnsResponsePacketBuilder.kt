package br.com.reboot.poc

object DnsResponsePacketBuilder {

    private const val IP_HEADER_SIZE = 20
    private const val UDP_HEADER_SIZE = 8
    private const val MTU = 1500

    fun build(
        request: ByteArray,
        requestLength: Int,
        dnsResponse: ByteArray
    ): ByteArray? {
        if (requestLength < 28 ||
            requestLength > request.size ||
            dnsResponse.size < 12
        ) return null

        fun u8(index: Int): Int =
            request[index].toInt() and 0xFF

        fun u16(index: Int): Int =
            (u8(index) shl 8) or u8(index + 1)

        if (u8(0) ushr 4 != 4) return null

        val ihl = (u8(0) and 0x0F) * 4

        if (ihl < 20 || ihl + 8 > requestLength) {
            return null
        }

        val requestTotalLength = u16(2)

        if (requestTotalLength < ihl + 8 ||
            requestTotalLength > requestLength
        ) return null

        if (u8(9) != 17) return null

        val fragmentInfo = u16(6)
        if ((fragmentInfo and 0x3FFF) != 0) {
            return null
        }

        val requestUdpLength = u16(ihl + 4)

        if (requestUdpLength < 8 ||
            ihl + requestUdpLength > requestTotalLength
        ) return null

        val sourcePort = u16(ihl)
        val destinationPort = u16(ihl + 2)

        if (destinationPort != 53) return null

        val queryPayloadStart = ihl + 8
        if (requestUdpLength < 20) return null

        // Confere o ID DNS entre consulta e resposta.
        if (request[queryPayloadStart] != dnsResponse[0] ||
            request[queryPayloadStart + 1] != dnsResponse[1]
        ) return null

        // A resposta deve ter o bit QR habilitado.
        if ((dnsResponse[2].toInt() and 0x80) == 0) {
            return null
        }

        val udpLength = UDP_HEADER_SIZE + dnsResponse.size
        val totalLength = IP_HEADER_SIZE + udpLength

        // Sem fragmentação IPv4 nesta primeira versão.
        if (totalLength > MTU) return null

        val packet = ByteArray(totalLength)

        fun put16(offset: Int, value: Int) {
            packet[offset] = (value ushr 8).toByte()
            packet[offset + 1] = value.toByte()
        }

        // Cabeçalho IPv4.
        packet[0] = 0x45.toByte()
        packet[1] = 0
        put16(2, totalLength)
        put16(4, 0)
        put16(6, 0)
        packet[8] = 64
        packet[9] = 17

        // IP de origem: DNS virtual original.
        System.arraycopy(
            request, 16, packet, 12, 4
        )

        // IP de destino: cliente original.
        System.arraycopy(
            request, 12, packet, 16, 4
        )

        put16(
            10,
            checksum(packet, 0, IP_HEADER_SIZE)
        )

        // Cabeçalho UDP.
        put16(20, destinationPort)
        put16(22, sourcePort)
        put16(24, udpLength)
        put16(26, 0)

        // Corpo da resposta DNS.
        System.arraycopy(
            dnsResponse,
            0,
            packet,
            IP_HEADER_SIZE + UDP_HEADER_SIZE,
            dnsResponse.size
        )

        // Pseudo-cabeçalho IPv4 para checksum UDP.
        val pseudo = ByteArray(12 + udpLength)

        System.arraycopy(packet, 12, pseudo, 0, 8)
        pseudo[8] = 0
        pseudo[9] = 17
        pseudo[10] = (udpLength ushr 8).toByte()
        pseudo[11] = udpLength.toByte()

        System.arraycopy(
            packet, IP_HEADER_SIZE,
            pseudo, 12, udpLength
        )

        val udpChecksum = checksum(
            pseudo, 0, pseudo.size
        )

        put16(
            26,
            if (udpChecksum == 0) 0xFFFF
            else udpChecksum
        )

        return packet
    }

    private fun checksum(
        data: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L
        var index = offset
        val end = offset + length

        while (index + 1 < end) {
            val high = data[index].toInt() and 0xFF
            val low = data[index + 1].toInt() and 0xFF

            sum += ((high shl 8) or low).toLong()
            index += 2
        }

        if (index < end) {
            sum +=
                ((data[index].toInt() and 0xFF) shl 8)
                    .toLong()
        }

        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }

        return sum.inv().toInt() and 0xFFFF
    }
}
