
package br.com.reboot.poc

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.reboot.poc.infrastructure.vpn.RebootDnsVpnService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnCommandContractTest {

    @Test
    fun unlockActionsAreDistinct() {
        val actions = listOf(
            RebootDnsVpnService.ACTION_START,
            RebootDnsVpnService.ACTION_STOP,
            RebootDnsVpnService.ACTION_REQUEST_UNLOCK,
            RebootDnsVpnService.ACTION_GET_UNLOCK_STATUS,
            RebootDnsVpnService.ACTION_CANCEL_UNLOCK,
            RebootDnsVpnService.ACTION_CONFIRM_UNLOCK
        )

        assertEquals(
            actions.size,
            actions.toSet().size
        )
    }

    @Test
    fun requestIdSurvivesIntentExtras() {
        val expectedId = "request-test-123"

        val intent = Intent().apply {
            putExtra(
                RebootDnsVpnService.EXTRA_REQUEST_ID,
                expectedId
            )
        }

        assertEquals(
            expectedId,
            intent.getStringExtra(
                RebootDnsVpnService.EXTRA_REQUEST_ID
            )
        )
    }

    @Test
    fun resultBundlePreservesUnlockStatus() {
        val bundle = Bundle().apply {
            putString(
                RebootDnsVpnService.KEY_STATE,
                "WAITING"
            )

            putLong(
                RebootDnsVpnService.KEY_REMAINING_MILLIS,
                60_000L
            )
        }

        assertEquals(
            "WAITING",
            bundle.getString(
                RebootDnsVpnService.KEY_STATE
            )
        )

        assertEquals(
            60_000L,
            bundle.getLong(
                RebootDnsVpnService.KEY_REMAINING_MILLIS
            )
        )
    }
}
