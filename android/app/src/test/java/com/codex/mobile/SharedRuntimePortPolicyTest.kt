package com.codex.mobile

import com.openminis.app.integration.MinisRuntimeBridgeRuntime
import com.openminis.app.integration.PocketLobsterPortPolicy
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

    @Test
    fun everyPrivateServiceUsesAChannelSpecificPort() {
        val packages = listOf(
            "com.codex.mobile.pocketlobster",
            "com.codex.mobile.pocketlobster.test",
            "com.codex.mobile.pocketlobster.beta",
            "com.openminis.app",
        )
        val selectors = listOf<(String) -> Int>(
            PocketLobsterPortPolicy::appServerPort,
            PocketLobsterPortPolicy::proxyPort,
            PocketLobsterPortPolicy::hostBridgePort,
            PocketLobsterPortPolicy::minisBridgePort,
            PocketLobsterPortPolicy::openClawGatewayPort,
            PocketLobsterPortPolicy::openClawControlUiPort,
        )

        selectors.forEach { select ->
            val ports = packages.map(select)
            assertEquals(ports.size, ports.toSet().size)
        }
        assertEquals(18943, PocketLobsterPortPolicy.appServerPort("com.codex.mobile.pocketlobster.beta"))
        assertEquals(18946, PocketLobsterPortPolicy.hostBridgePort("com.codex.mobile.pocketlobster.beta"))
        assertEquals(18809, PocketLobsterPortPolicy.openClawGatewayPort("com.codex.mobile.pocketlobster.beta"))
        assertEquals(19021, PocketLobsterPortPolicy.openClawControlUiPort("com.codex.mobile.pocketlobster.beta"))
    }
}
