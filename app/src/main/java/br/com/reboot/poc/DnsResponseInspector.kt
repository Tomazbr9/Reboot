package br.com.reboot.poc

object DnsResponseInspector {

    data class Inspection(
        val responseCode: Int,
        val hasZeroIpv4: Boolean,
        val ipv4Answers: Int
    )

    fun inspect(message: ByteArray): Inspection? {
        if (message.size < 12) return null

        fun u8(i: Int) = message[i].toInt() and 0xFF

        fun u16(i: Int) =
            (u8(i) shl 8) or u8(i + 1)

        // Somente mensagens de resposta DNS.
        if ((u8(2) and 0x80) == 0) return null

        val rcode = u8(3) and 0x0F
        val questionCount = u16(4)
        val answerCount = u16(6)

        var offset = 12

        fun skipName(start: Int): Int? {
            var pos = start

            while (pos < message.size) {
                val size = u8(pos)

                when {
                    size == 0 -> return pos + 1

                    (size and 0xC0) == 0xC0 -> {
                        return if (pos + 1 < message.size) {
                            pos + 2
                        } else null
                    }

                    (size and 0xC0) != 0 -> return null

                    else -> {
                        pos += 1 + size
                    }
                }
            }

            return null
        }

        repeat(questionCount) {
            offset = skipName(offset) ?: return null

            if (offset + 4 > message.size) return null

            // QTYPE + QCLASS
            offset += 4
        }

        var zeroIpv4 = false
        var ipv4Answers = 0

        repeat(answerCount) {
            offset = skipName(offset) ?: return null

            if (offset + 10 > message.size) return null

            val type = u16(offset)
            val dnsClass = u16(offset + 2)
            val dataLength = u16(offset + 8)

            offset += 10

            if (dataLength > message.size - offset) {
                return null
            }

            if (type == 1 &&
                dnsClass == 1 &&
                dataLength == 4
            ) {
                ipv4Answers++

                val allZero = (0 until 4).all {
                    u8(offset + it) == 0
                }

                if (allZero) zeroIpv4 = true
            }

            offset += dataLength
        }

        return Inspection(
            responseCode = rcode,
            hasZeroIpv4 = zeroIpv4,
            ipv4Answers = ipv4Answers
        )
    }
}
