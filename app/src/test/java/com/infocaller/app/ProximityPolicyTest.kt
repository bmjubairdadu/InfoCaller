package com.infocaller.app

import android.telecom.Call
import com.infocaller.app.util.ProximityPolicy
import org.junit.Assert.*
import org.junit.Test

class ProximityPolicyTest {

    @Test
    fun testHeldOnlyForActiveEarpieceCall() {
        assertTrue(ProximityPolicy.shouldHold(Call.STATE_ACTIVE, false))
    }

    @Test
    fun testReleasedOnSpeaker() {
        assertFalse(ProximityPolicy.shouldHold(Call.STATE_ACTIVE, true))
    }

    @Test
    fun testReleasedWhileRinging() {
        assertFalse(ProximityPolicy.shouldHold(Call.STATE_RINGING, false))
    }

    @Test
    fun testReleasedWhileDialing() {
        assertFalse(ProximityPolicy.shouldHold(Call.STATE_DIALING, false))
    }

    @Test
    fun testReleasedOnHold() {
        assertFalse(ProximityPolicy.shouldHold(Call.STATE_HOLDING, false))
    }

    @Test
    fun testReleasedWhenDisconnected() {
        assertFalse(ProximityPolicy.shouldHold(Call.STATE_DISCONNECTED, false))
    }
}
