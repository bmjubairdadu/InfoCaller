package com.infocaller.app

import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.util.IdentifierRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentifierRoutingTest {

    @Test
    fun emailRoutesToEmail() {
        assertEquals(IdentifierType.EMAIL, IdentifierRouter.routeType("Test@Example.com"))
        assertEquals(IdentifierType.EMAIL, IdentifierRouter.routeType("  user.name+tag@gmail.com  "))
    }

    @Test
    fun nidPipeRoutesToNid() {
        assertEquals(IdentifierType.NID, IdentifierRouter.routeType("1234567890|1990-01-01"))
    }

    @Test
    fun plainNidLengthsRouteToNid() {
        assertEquals(IdentifierType.NID, IdentifierRouter.routeType("1234567890"))
        assertEquals(IdentifierType.NID, IdentifierRouter.routeType("1234567890123"))
        assertEquals(IdentifierType.NID, IdentifierRouter.routeType("12345678901234567"))
    }

    @Test
    fun phonesRouteToPhone() {
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("+8801785917145"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("8801785917145"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("01785917145"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("01785-917145"))
    }

    @Test
    fun handlesRouteToUsername() {
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("@jubairdadubm"))
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("jubairdadu143"))
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("jubairhose143"))
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("jubairdaduff"))
    }

    @Test
    fun numericStringsStayPhone() {
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("01785 917145"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("123"))
    }
}
