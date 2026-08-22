package com.codex.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketLobsterApplicationTest {
    private val packageName = "com.codex.mobile.pocketlobster.beta"

    @Test
    fun hostProcessDoesNotInitializeMinisRuntime() {
        assertFalse(
            PocketLobsterApplication.isMinisRuntimeProcess(packageName, packageName),
        )
    }

    @Test
    fun minisAndAcraProcessesInitializeMinisRuntime() {
        assertTrue(
            PocketLobsterApplication.isMinisRuntimeProcess("$packageName:minis", packageName),
        )
        assertTrue(
            PocketLobsterApplication.isMinisRuntimeProcess("$packageName:acra", packageName),
        )
    }

    @Test
    fun unrelatedProcessDoesNotInitializeMinisRuntime() {
        assertFalse(
            PocketLobsterApplication.isMinisRuntimeProcess("com.openminis.app", packageName),
        )
    }

    @Test
    fun shizukuProviderLivesOnlyInHostProcess() {
        assertTrue(
            PocketLobsterApplication.isShizukuProviderProcess(packageName, packageName),
        )
        assertFalse(
            PocketLobsterApplication.isShizukuProviderProcess("$packageName:minis", packageName),
        )
    }

    @Test
    fun minisBridgeLivesOnlyInMinisProcess() {
        assertTrue(PocketLobsterApplication.isMinisProcess("$packageName:minis", packageName))
        assertFalse(PocketLobsterApplication.isMinisProcess(packageName, packageName))
        assertFalse(PocketLobsterApplication.isMinisProcess("$packageName:acra", packageName))
    }
}
