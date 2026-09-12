package com.openminis.app.integration

/** Keeps concurrently installed Pocket Lobster channels on separate loopback ports. */
object PocketLobsterPortPolicy {
    private const val PRODUCTION_PACKAGE = "com.codex.mobile.pocketlobster"
    private const val OPERATOR_PACKAGE = "com.codex.mobile.pocketlobster.test"
    private const val BETA_PACKAGE = "com.codex.mobile.pocketlobster.beta"

    fun appServerPort(packageName: String): Int = 18923 + channelOffset(packageName)

    fun proxyPort(packageName: String): Int = 18924 + channelOffset(packageName)

    fun hostBridgePort(packageName: String): Int = 18926 + channelOffset(packageName)

    fun minisBridgePort(packageName: String): Int = 18927 + channelOffset(packageName)

    fun openClawGatewayPort(packageName: String): Int = 18789 + channelOffset(packageName)

    fun openClawControlUiPort(packageName: String): Int = 19001 + channelOffset(packageName)

    private fun channelOffset(packageName: String): Int = when (packageName) {
        PRODUCTION_PACKAGE -> 0
        OPERATOR_PACKAGE -> 10
        BETA_PACKAGE -> 20
        else -> 30
    }
}
