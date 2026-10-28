package br.com.reboot.poc

import org.junit.Assert.*
import org.junit.Test

class DnsProtocolTest {

    private fun put16(
        data: ByteArray,
        offset: Int,
        value: Int
    ) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun u16(
        data: ByteArray,
        offset: Int
    ): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0

        while (i < data.size) {
            val high = data[i].toInt() and 0xFF
            val low = if (i + 1 < data.size) {
                data[i + 1].toInt() and 0xFF
            } else 0

            sum += ((high shl 8) or low).toLong()
            i += 2
        }

        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        return sum.inv().toInt() and 0xFFFF
    }

    private fun queryPayload(): ByteArray {
        // Cabeçalho DNS de 12 bytes:
        // ID 0x1234, flags de consulta, QDCOUNT=1.
        return byteArrayOf(
            0x12, 0x34,
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,

            // Nome: a.test
            0x01, 0x61,
            0x04, 0x74, 0x65, 0x73, 0x74,
            0x00,

            // QTYPE=A, QCLASS=IN
            0x00, 0x01,
            0x00, 0x01
        )
    }

    private fun requestPacket(): ByteArray {
        val dns = queryPayload()
        val udpLength = 8 + dns.size
        val totalLength = 20 + udpLength

        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        put16(packet, 2, totalLength)
        packet[8] = 64
        packet[9] = 17

        // Origem: 10.111.0.1
        packet[12] = 10
        packet[13] = 111
        packet[14] = 0
        packet[15] = 1

        // Destino DNS virtual: 10.111.0.2
        packet[16] = 10
        packet[17] = 111
        packet[18] = 0
        packet[19] = 2

        put16(packet, 20, 54321)
        put16(packet, 22, 53)
        put16(packet, 24, udpLength)

        dns.copyInto(packet, 28)

        return packet
    }

    private fun responsePayload(): ByteArray {
        val response = queryPayload().copyOf()

        // Marca como resposta DNS sem erro.
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()

        return response
    }

    @Test
    fun classifiesUdpDnsPacket() {
        val packet = requestPacket()

        assertEquals(
            PacketKind.DNS_UDP_53,
            DnsPacketClassifier.classify(
                packet, packet.size
            )
        )
    }

    @Test
    fun rejectsIncompleteIpv4Packet() {
        val packet = byteArrayOf(0x45, 0x00)

        assertEquals(
            PacketKind.MALFORMED,
            DnsPacketClassifier.classify(
                packet, packet.size
            )
        )
    }

    @Test
    fun identifiesNonDnsUdpTraffic() {
        val packet = requestPacket()
        put16(packet, 22, 1234)

        assertEquals(
            PacketKind.OTHER,
            DnsPacketClassifier.classify(
                packet, packet.size
            )
        )
    }

    @Test
    fun extractsDnsQueryAndSourcePort() {
        val packet = requestPacket()

        val query = DnsQueryExtractor.extract(
            packet, packet.size
        )

        assertNotNull(query)
        assertEquals(54321, query!!.sourcePort)
        assertEquals(0x1234, query.transactionId)
        assertArrayEquals(
            queryPayload(),
            query.payload
        )
    }

    @Test
    fun buildsResponseWithReversedAddressesAndPorts() {
        val request = requestPacket()

        val response = DnsResponsePacketBuilder.build(
            request,
            request.size,
            responsePayload()
        )

        assertNotNull(response)
        val packet = response!!

        // IP origem = DNS virtual.
        assertArrayEquals(
            request.copyOfRange(16, 20),
            packet.copyOfRange(12, 16)
        )

        // IP destino = cliente.
        assertArrayEquals(
            request.copyOfRange(12, 16),
            packet.copyOfRange(16, 20)
        )

        assertEquals(53, u16(packet, 20))
        assertEquals(54321, u16(packet, 22))

        assertEquals(
            packet.size,
            u16(packet, 2)
        )
    }

    @Test
    fun buildsValidIpv4AndUdpChecksums() {
        val request = requestPacket()

        val packet = DnsResponsePacketBuilder.build(
            request,
            request.size,
            responsePayload()
        )!!

        val ipHeader = packet.copyOfRange(0, 20)

        // Checksum válido resulta em zero
        // quando o cabeçalho completo é verificado.
        assertEquals(0, checksum(ipHeader))

        val udpLength = u16(packet, 24)
        val pseudo = ByteArray(12 + udpLength)

        packet.copyInto(
            pseudo,
            destinationOffset = 0,
            startIndex = 12,
            endIndex = 20
        )

        pseudo[9] = 17
        put16(pseudo, 10, udpLength)

        packet.copyInto(
            pseudo,
            destinationOffset = 12,
            startIndex = 20,
            endIndex = 20 + udpLength
        )

        assertEquals(0, checksum(pseudo))
    }

    @Test
    fun rejectsResponseWithDifferentTransactionId() {
        val request = requestPacket()
        val response = responsePayload()

        response[0] = 0x56
        response[1] = 0x78

        assertNull(
            DnsResponsePacketBuilder.build(
                request,
                request.size,
                response
            )
        )
    }

    @Test
    fun rejectsOversizedResponse() {
        val request = requestPacket()
        val response = ByteArray(1500)

        response[0] = 0x12
        response[1] = 0x34
        response[2] = 0x80.toByte()

        assertNull(
            DnsResponsePacketBuilder.build(
                request,
                request.size,
                response
            )
        )
    }
}
