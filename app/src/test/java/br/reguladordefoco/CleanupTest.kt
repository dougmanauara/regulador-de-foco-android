package br.reguladordefoco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day

    private fun app(
        pkg: String,
        preinstalled: Boolean = false,
        enabled: Boolean = true,
        installedDaysAgo: Long = 200,
        usedDaysAgo: Long? = 60,
        size: Long = 100_000_000,
    ) = Cleanup.App(
        pkg, pkg, preinstalled, enabled,
        installedMs = now - installedDaysAgo * day,
        lastUsedMs = usedDaysAgo?.let { now - it * day } ?: 0L,
        sizeBytes = size,
    )

    @Test
    fun bloatOnlyWhenInstalledAndEnabled() {
        val found = Cleanup.bloatFound(
            listOf(
                app("com.samsung.android.arzone", preinstalled = true),
                app("com.facebook.appmanager", preinstalled = true, enabled = false),
                app("com.whatsapp"),
            )
        )
        assertEquals(listOf("com.samsung.android.arzone"), found.map { it.first.pkg })
    }

    @Test
    fun bloatListHasNoDuplicatesNorSystemEssentials() {
        val pkgs = Cleanup.BLOAT.map { it.pkg }
        assertEquals(pkgs.size, pkgs.toSet().size)
        val essentials = listOf("com.android.vending", "com.google.android.gms", "com.samsung.android.dialer",
            "com.samsung.android.messaging", "com.samsung.android.honeyboard", "com.android.systemui")
        assertTrue(essentials.none { it in pkgs })
    }

    @Test
    fun unusedSkipsRecentSystemOwnAndDisabledApps() {
        val list = Cleanup.unused(
            listOf(
                app("big.unused", size = 900_000_000),
                app("small.unused", size = 50_000_000),
                app("never.opened", usedDaysAgo = null, size = 200_000_000),
                app("used.yesterday", usedDaysAgo = 1),
                app("installed.last.week", installedDaysAgo = 7, usedDaysAgo = null),
                app("system.thing", preinstalled = true),
                app("disabled.app", enabled = false),
                app("br.reguladordefoco"),
                app("com.linkedin.android"),
            ),
            now, "br.reguladordefoco"
        )
        assertEquals(listOf("big.unused", "never.opened", "small.unused"), list.map { it.pkg })
    }

    @Test
    fun texts() {
        assertEquals("nunca usado", Cleanup.lastUsedText(0L, now))
        assertEquals("usado há 45 dias", Cleanup.lastUsedText(now - 45 * day, now))
        assertEquals("1,2 GB", Cleanup.sizeText(1_234_000_000))
        assertEquals("340 MB", Cleanup.sizeText(340_500_000))
    }

    @Test
    fun fluidityRules() {
        assertTrue(Cleanup.storageLow(10, 100))
        assertFalse(Cleanup.storageLow(30, 100))
        assertTrue(Cleanup.animationsSlow(1f))
        assertFalse(Cleanup.animationsSlow(0.5f))
        assertFalse(Cleanup.animationsSlow(0f))
    }

    @Test
    fun commandsAreArgumentListsForValidPackagesOnly() {
        assertEquals(
            listOf("pm", "disable-user", "--user", "0", "com.samsung.android.arzone"),
            Cleanup.disableCommand("com.samsung.android.arzone")
        )
        assertEquals(listOf("pm", "enable", "--user", "0", "com.facebook.system"), Cleanup.enableCommand("com.facebook.system"))
        for (bad in listOf("com.x; reboot", "com.x && rm", "nopackage", "", "com.x\nreboot", "-k com.x")) {
            try {
                Cleanup.disableCommand(bad)
                throw AssertionError("aceitou: $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
        assertTrue(Cleanup.BLOAT.all { runCatching { Cleanup.disableCommand(it.pkg) }.isSuccess })
    }

    @Test
    fun animationCommandsSetAllThreeScales() {
        val cmds = Cleanup.animationCommands(Cleanup.FAST_ANIMATION)
        assertEquals(3, cmds.size)
        assertTrue(cmds.all { it.take(3) == listOf("settings", "put", "global") && it.last() == "0.5" })
    }
}
