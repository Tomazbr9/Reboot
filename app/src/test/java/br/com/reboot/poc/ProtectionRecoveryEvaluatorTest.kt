package br.com.reboot.poc

import br.com.reboot.poc.application.protection.ProtectionRecoveryDecision
import br.com.reboot.poc.application.protection.ProtectionRecoveryEvaluator
import br.com.reboot.poc.application.protection.VpnOperationalSnapshot
import br.com.reboot.poc.domain.protection.ProtectionSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ProtectionRecoveryEvaluatorTest {

    private val sessionA = UUID.randomUUID().toString()
    private val sessionB = UUID.randomUUID().toString()

    private val evaluator = ProtectionRecoveryEvaluator()

    private fun record(
        key: String = sessionA,
        boot: Int = 3
    ) = ProtectionSessionRecord(
        sessionKey = key,
        bootCount = boot,
        activatedAtElapsedMillis = 100_000L
    )

    @Test
    fun noSavedSessionReturnsNoRecord() {
        val decision = evaluator.evaluate(
            saved = null,
            snapshot = VpnOperationalSnapshot.Unknown,
            currentBootCount = 3
        )

        assertEquals(
            ProtectionRecoveryDecision.NO_SAVED_SESSION,
            decision
        )
    }

    @Test
    fun unknownStatePreservesUncertainty() {
        val decision = evaluator.evaluate(
            saved = record(),
            snapshot = VpnOperationalSnapshot.Unknown,
            currentBootCount = 3
        )

        assertEquals(
            ProtectionRecoveryDecision.WAITING_FOR_VERIFICATION,
            decision
        )
    }

    @Test
    fun matchingActiveSessionIsRecognized() {
        val decision = evaluator.evaluate(
            saved = record(),
            snapshot = VpnOperationalSnapshot.Active(sessionA),
            currentBootCount = 3
        )

        assertEquals(
            ProtectionRecoveryDecision.MATCHED_ACTIVE_SESSION,
            decision
        )
    }

    @Test
    fun differentActiveSessionIsRecognized() {
        val decision = evaluator.evaluate(
            saved = record(),
            snapshot = VpnOperationalSnapshot.Active(sessionB),
            currentBootCount = 3
        )

        assertEquals(
            ProtectionRecoveryDecision.DIFFERENT_ACTIVE_SESSION,
            decision
        )
    }

    @Test
    fun confirmedInactiveSessionIsRecognized() {
        val decision = evaluator.evaluate(
            saved = record(),
            snapshot = VpnOperationalSnapshot.Inactive,
            currentBootCount = 3
        )

        assertEquals(
            ProtectionRecoveryDecision.CONFIRMED_INACTIVE,
            decision
        )
    }

    @Test
    fun previousBootIsRecognizedAfterVerification() {
        val decision = evaluator.evaluate(
            saved = record(boot = 3),
            snapshot = VpnOperationalSnapshot.Inactive,
            currentBootCount = 4
        )

        assertEquals(
            ProtectionRecoveryDecision.PREVIOUS_BOOT_SESSION,
            decision
        )
    }

    @Test
    fun unknownStateDoesNotAssumeRebootRecovery() {
        val decision = evaluator.evaluate(
            saved = record(boot = 3),
            snapshot = VpnOperationalSnapshot.Unknown,
            currentBootCount = 4
        )

        assertEquals(
            ProtectionRecoveryDecision.WAITING_FOR_VERIFICATION,
            decision
        )
    }
}
