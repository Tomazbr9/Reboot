package br.com.reboot.poc.infrastructure.dns

enum class PacketKind {
    DNS_UDP_53,
    OTHER,
    MALFORMED
}

object DnsPacketClassifier {

    fun classify(
        buffer: ByteArray,
        length: Int
    ): PacketKind {
        if (length < 1 || length > buffer.size) {
            return PacketKind.MALFORMED
        }

        fun u8(index: Int): Int =
            buffer[index].toInt() and 0xFF

        val version = u8(0) ushr 4

        // O primeiro PoC suporta apenas IPv4.
        if (version != 4) return PacketKind.OTHER

        if (length < 20) return PacketKind.MALFORMED

        val headerLength = (u8(0) and 0x0F) * 4

        if (headerLength < 20 || headerLength > length) {
            return PacketKind.MALFORMED
        }

        val totalLength = (u8(2) shl 8) or u8(3)

        if (totalLength < headerLength ||
            totalLength > length
        ) {
            return PacketKind.MALFORMED
        }

        // UDP = protocolo IP 17.
        if (u8(9) != 17) return PacketKind.OTHER

        // Ignoramos pacotes IPv4 fragmentados neste PoC.
        val fragmentInfo =
            (u8(6) shl 8) or u8(7)

        val isFragmented =
            (fragmentInfo and 0x3FFF) != 0

        if (isFragmented) return PacketKind.OTHER

        if (totalLength < headerLength + 8) {
            return PacketKind.MALFORMED
        }

        val udpOffset = headerLength

        val destinationPort =
            (u8(udpOffset + 2) shl 8) or
                    u8(udpOffset + 3)

        val udpLength =
            (u8(udpOffset + 4) shl 8) or
                    u8(udpOffset + 5)

        if (udpLength < 8 ||
            udpOffset + udpLength > totalLength
        ) {
            return PacketKind.MALFORMED
        }

        return if (destinationPort == 53) {
            PacketKind.DNS_UDP_53
        } else {
            PacketKind.OTHER
        }
    }
}
