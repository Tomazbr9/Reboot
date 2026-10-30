package br.com.reboot.poc

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import br.com.reboot.poc.domain.protection.PendingStopCleanup
import br.com.reboot.poc.infrastructure.persistence.PendingStopCleanupStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PendingStopCleanupStorageTest {

    @Test
    fun markerSurvivesStorageRecreation() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val storage = PendingStopCleanupStorage(context)
        val sessionKey = UUID.randomUUID().toString()

        try {
            storage.save(
                PendingStopCleanup(sessionKey)
            )

            val recreated = PendingStopCleanupStorage(context)

            assertEquals(
                sessionKey,
                recreated.load()?.sessionKey
            )
        } finally {
            storage.clear()
        }
    }

    @Test
    fun clearedMarkerIsNotRestored() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val storage = PendingStopCleanupStorage(context)
        val sessionKey = UUID.randomUUID().toString()

        try {
            storage.save(PendingStopCleanup(sessionKey))
            storage.clear()

            assertNull(
                PendingStopCleanupStorage(context).load()
            )
        } finally {
            storage.clear()
        }
    }
}
