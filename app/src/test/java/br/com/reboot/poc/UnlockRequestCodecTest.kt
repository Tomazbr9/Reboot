package br.com.reboot.poc

import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
import br.com.reboot.poc.infrastructure.persistence.UnlockRequestCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UnlockRequestCodecTest {

    private fun example() = StoredUnlockRequest(
        requestId = UUID.randomUUID().toString(),
        protectionSessionKey =
            UUID.randomUUID().toString(),
        delay = UnlockDelay.HOURS_2,
        requestedAtElapsedMillis = 100_000L,
        bootCount = 5
    )

    @Test
    fun encodesAndDecodesRequest() {
        val original = example()

        val restored = UnlockRequestCodec.decode(
            UnlockRequestCodec.encode(original)
        )

        assertEquals(original, restored)
    }

    @Test
    fun preservesRemainingTime() {
        val original = example()

        val restored = UnlockRequestCodec.decode(
            UnlockRequestCodec.encode(original)
        )!!

        assertEquals(
            6_600_000L,
            restored.remainingMillis(700_000L)
        )
    }

    @Test
    fun deadlineDoesNotAutomaticallyStopVpn() {
        val request = example()

        assertTrue(
            request.isReady(
                100_000L + 7_200_000L
            )
        )

        // A classe não contém qualquer operação
        // que encerre a VPN.
        assertEquals(
            0L,
            request.remainingMillis(
                100_000L + 7_200_000L
            )
        )
    }

    @Test
    fun rejectsInvalidVersion() {
        val encoded = UnlockRequestCodec.encode(
            example()
        )

        assertNull(
            UnlockRequestCodec.decode(
                encoded.replaceFirst("v1|", "v9|")
            )
        )
    }

    @Test
    fun rejectsInvalidDuration() {
        val encoded = UnlockRequestCodec.encode(
            example()
        )

        assertNull(
            UnlockRequestCodec.decode(
                encoded.replace(
                    "HOURS_2",
                    "INVALID_DURATION"
                )
            )
        )
    }

    @Test
    fun rejectsInvalidStoredIdentity() {
        val encoded = UnlockRequestCodec.encode(
            example()
        )

        val parts = encoded.split("|").toMutableList()
        parts[2] = "invalid-session-id"

        assertNull(
            UnlockRequestCodec.decode(
                parts.joinToString("|")
            )
        )
    }
}
