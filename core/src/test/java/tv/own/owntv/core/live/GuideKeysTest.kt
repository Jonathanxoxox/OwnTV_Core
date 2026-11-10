package tv.own.owntv.core.live

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity

/** The one key every guide read uses: manual match, then the app's override, then the channel's own id. */
class GuideKeysTest {

    @After
    fun reset() {
        GuideKeys.override = null
    }

    @Test
    fun `without an override the channels own id is used`() {
        assertEquals("bbc1", GuideKeys.keyOf(channel("bbc1"), SectionCustomizations()))
    }

    @Test
    fun `the id is trimmed and lowercased and empty means none`() {
        assertEquals("bbc.one", GuideKeys.keyOf(channel("  BBC.One "), SectionCustomizations()))
        assertNull(GuideKeys.keyOf(channel("   "), SectionCustomizations()))
        assertNull(GuideKeys.keyOf(channel(null), SectionCustomizations()))
    }

    @Test
    fun `the override wins over the channels own id`() {
        GuideKeys.override = { " Magenta.1 " }
        assertEquals("magenta.1", GuideKeys.keyOf(channel("bbc1"), SectionCustomizations()))
    }

    @Test
    fun `a manual match wins over the override`() {
        val ch = channel("bbc1")
        val cust = SectionCustomizations(epgMatches = mapOf(CustomizeKeys.channel(ch) to "Manual"))
        GuideKeys.override = { "magenta.1" }
        assertEquals("manual", GuideKeys.keyOf(ch, cust))
    }

    @Test
    fun `a blank override falls through to the channels id`() {
        GuideKeys.override = { "  " }
        assertEquals("bbc1", GuideKeys.keyOf(channel("bbc1"), SectionCustomizations()))
    }

    @Test
    fun `a null override answer falls through to the channels id`() {
        GuideKeys.override = { null }
        assertEquals("bbc1", GuideKeys.keyOf(channel("bbc1"), SectionCustomizations()))
    }

    @Test
    fun `a throwing override falls through to the channels id`() {
        GuideKeys.override = { error("boom") }
        assertEquals("bbc1", GuideKeys.keyOf(channel("bbc1"), SectionCustomizations()))
    }

    private fun channel(epgId: String?) = ChannelEntity(
        sourceId = 1,
        remoteId = "r1",
        name = "Channel",
        streamUrl = "http://example.invalid/stream",
        epgChannelId = epgId,
    )
}
