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
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("+8801712345678"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("8801712345678"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("01712345678"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("01712-345678"))
    }

    @Test
    fun handlesRouteToUsername() {
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("@samplehandle01"))
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("testuser143"))
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("demo_handle02"))
        assertEquals(IdentifierType.USERNAME, IdentifierRouter.routeType("sample_user99"))
    }

    @Test
    fun numericStringsStayPhone() {
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("01785 917145"))
        assertEquals(IdentifierType.PHONE, IdentifierRouter.routeType("123"))
    }

    @Test
    fun bareAtHandleIsNotEmail() {
        assertEquals(false, IdentifierRouter.isEmail("@samplehandle01"))
        assertEquals(false, IdentifierRouter.isEmail("nodot@domain"))
        assertEquals(false, IdentifierRouter.isEmail("plain word"))
        assertEquals(true, IdentifierRouter.isEmail("user.name+tag@gmail.com"))
        assertEquals(true, IdentifierRouter.isEmail("  Test@Example.com  "))
    }
}
