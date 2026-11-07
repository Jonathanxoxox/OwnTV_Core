package tv.own.owntv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cached cast column: actors and, since version 2, the director in the same JSON. */
class MetadataCastTest {

    private val cast = listOf(CastMember("Tom Hanks", "/hanks.jpg"), CastMember("Matt Damon"))
    private val directors = listOf(CastMember("Steven Spielberg", "/spielberg.jpg"))

    @Test
    fun `actors and director come back apart`() {
        val json = MetadataCast.serialize(cast, directors)
        assertEquals(cast, MetadataCast.parse(json))
        assertEquals(directors, MetadataCast.directors(json))
        assertFalse(MetadataCast.isLegacyFormat(json))
    }

    @Test
    fun `rows without the version marker are fetched again, an empty list is not`() {
        assertTrue("names only", MetadataCast.isLegacyFormat("""["Tom Hanks","Matt Damon"]"""))
        assertTrue("photos but no director yet", MetadataCast.isLegacyFormat("""[{"n":"Tom Hanks","p":"/a.jpg"}]"""))
        assertFalse(MetadataCast.isLegacyFormat("[]"))
        assertFalse(MetadataCast.isLegacyFormat(null))
        // Older rows still read: their actors, no director.
        assertEquals(listOf(CastMember("Tom Hanks", "/a.jpg")), MetadataCast.parse("""[{"n":"Tom Hanks","p":"/a.jpg"}]"""))
        assertEquals(emptyList<CastMember>(), MetadataCast.directors("""[{"n":"Tom Hanks","p":"/a.jpg"}]"""))
    }

    @Test
    fun `a title with a director but no actors keeps the director`() {
        val json = MetadataCast.serialize(emptyList(), directors)
        assertEquals(emptyList<CastMember>(), MetadataCast.parse(json))
        assertEquals(directors, MetadataCast.directors(json))
    }
}
