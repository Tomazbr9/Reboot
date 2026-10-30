
package br.com.reboot.poc

import br.com.reboot.poc.application.ports.PersistentUnlockRepository
import br.com.reboot.poc.application.unlock.UnlockStopAuthorization
import br.com.reboot.poc.application.unlock.UnlockStopDecision
import br.com.reboot.poc.domain.unlock.StoredUnlockRequest
import br.com.reboot.poc.domain.unlock.UnlockDelay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class UnlockStopAuthorizationTest {

    private class FakeRepository :
        PersistentUnlockRepository {

        var stored: StoredUnlockRequest? = null

        override fun save(request: StoredUnlockRequest) {
            stored = request
        }

        override fun load(): StoredUnlockRequest? = stored

        override fun clear() {
            stored = null
        }
    }

    private val repository = FakeRepository()

    private val sessionA = UUID.randomUUID().toString()
    private val sessionB = UUID.randomUUID().toString()

    private var now = 100_000L
    private var boot = 3

    private val authorization = UnlockStopAuthorization(
        repository = repository,
        elapsedRealtimeMillis = { now },
        bootCount = { boot }
    )

    private fun createRequest(): StoredUnlockRequest {
        val request = StoredUnlockRequest.create(
            protectionSessionKey = sessionA,
            delay = UnlockDelay.MINUTES_30,
            nowElapsedMillis = now,
            bootCount = boot
        )

        repository.save(request)
        return request
    }

    @Test
    fun waitingRequestCannotStopVpn() {
        val request = createRequest()

        val decision = authorization.evaluate(
            activeSessionKey = sessionA,
            requestId = request.requestId,
            vpnIsActive = true
        )

        assertEquals(
            UnlockStopDecision.WAITING,
            decision
        )
    }

    @Test
    fun readyRequestCanAuthorizeConfirmation() {
        val request = createRequest()

        now += 1_800_000L

        val decision = authorization.evaluate(
            activeSessionKey = sessionA,
            requestId = request.requestId,
            vpnIsActive = true
        )

        assertEquals(
            UnlockStopDecision.AUTHORIZED,
            decision
        )
    }

    @Test
    fun wrongRequestIdCannotStopVpn() {
        createRequest()

        now += 1_800_000L

        val decision = authorization.evaluate(
            activeSessionKey = sessionA,
            requestId = UUID.randomUUID().toString(),
            vpnIsActive = true
        )

        assertEquals(
            UnlockStopDecision.REQUEST_MISMATCH,
            decision
        )
    }

    @Test
    fun oldSessionCannotStopNewSession() {
        val request = createRequest()

        now += 1_800_000L

        val decision = authorization.evaluate(
            activeSessionKey = sessionB,
            requestId = request.requestId,
            vpnIsActive = true
        )

        assertEquals(
            UnlockStopDecision.SESSION_MISMATCH,
            decision
        )
    }

    @Test
    fun inactiveVpnCannotBeAuthorized() {
        val request = createRequest()

        now += 1_800_000L

        val decision = authorization.evaluate(
            activeSessionKey = sessionA,
            requestId = request.requestId,
            vpnIsActive = false
        )

        assertEquals(
            UnlockStopDecision.VPN_NOT_ACTIVE,
            decision
        )
    }

    @Test
    fun cancelledRequestCannotAuthorizeStop() {
        val request = createRequest()

        repository.clear()
        now += 1_800_000L

        val decision = authorization.evaluate(
            activeSessionKey = sessionA,
            requestId = request.requestId,
            vpnIsActive = true
        )

        assertEquals(
            UnlockStopDecision.REQUEST_NOT_FOUND,
            decision
        )
    }

    @Test
    fun previousBootCannotAuthorizeStop() {
        val request = createRequest()

        boot = 4
        now = 1_000L

        val decision = authorization.evaluate(
            activeSessionKey = sessionA,
            requestId = request.requestId,
            vpnIsActive = true
        )

        assertEquals(
            UnlockStopDecision.PREVIOUS_BOOT,
            decision
        )
    }

    @Test
    fun deadlineDoesNotAutomaticallyDeleteRequest() {
        val request = createRequest()

        now += 1_800_000L

        authorization.evaluate(
            activeSessionKey = sessionA,
            requestId = request.requestId,
            vpnIsActive = true
        )

        assertEquals(
            request,
            repository.load()
        )
    }
}
