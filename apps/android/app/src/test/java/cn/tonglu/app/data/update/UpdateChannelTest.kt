package cn.tonglu.app.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateChannelTest {
    @Test fun freshPreviewInstallationFollowsPreviewAndExplicitChoiceSurvivesUpgrade() {
        assertEquals(UpdateChannel.Preview, UpdateChannel.saved(null, "0.16.0-beta.1"))
        assertEquals(UpdateChannel.Stable, UpdateChannel.saved(null, "0.15.0"))
        assertEquals(UpdateChannel.Preview, UpdateChannel.saved("preview", "0.16.0"))
        assertEquals(UpdateChannel.Stable, UpdateChannel.saved("stable", "0.16.1-beta.1"))
    }
    @Test fun previewAlsoReceivesNewerWeeklyStableButStableNeverReadsPreview() {
        assertEquals(listOf(UpdateChannel.Preview, UpdateChannel.Stable), UpdateChannel.Preview.sources)
        assertEquals(listOf(UpdateChannel.Stable), UpdateChannel.Stable.sources)
        assertTrue(UpdateChannel.Preview.accepts("0.16.0"))
        assertFalse(UpdateChannel.Stable.accepts("0.16.0-beta.1"))
        assertEquals("https://dl.useall.top/app-updates/preview.json", UpdateChannel.Preview.manifestUrl)
    }
    @Test(expected = IllegalArgumentException::class) fun misroutedPreviewManifestCannotAppearAsStable() {
        parseUpdateRelease("""{"schemaVersion":1,"channel":"preview"}""", UpdateChannel.Stable)
    }
}
