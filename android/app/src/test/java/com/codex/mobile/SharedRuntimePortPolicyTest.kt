package com.codex.mobile

import com.openminis.app.integration.MinisRuntimeBridgeRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SharedRuntimePortPolicyTest {
    @Test
    fun installedChannelsUseDistinctMinisBridgePorts() {
        val production = MinisRuntimeBridgeRuntime.portForPackage("com.codex.mobile.pocketlobster")
        val operator = MinisRuntimeBridgeRuntime.portForPackage("com.codex.mobile.pocketlobster.test")
        val beta = MinisRuntimeBridgeRuntime.portForPackage("com.codex.mobile.pocketlobster.beta")

        assertEquals(18927, production)
        assertEquals(18937, operator)
        assertEquals(18947, beta)
        assertNotEquals(production, operator)
        assertNotEquals(production, beta)
        assertNotEquals(operator, beta)
    }

    @Test
    fun standaloneOpenMinisDoesNotCollideWithPocketLobsterChannels() {
        assertEquals(18957, MinisRuntimeBridgeRuntime.portForPackage("com.openminis.app"))
    }
}
