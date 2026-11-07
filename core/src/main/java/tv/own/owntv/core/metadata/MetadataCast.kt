package tv.own.owntv.core.metadata

import org.json.JSONArray
import org.json.JSONObject

/** One credited actor: the name shown, plus the TMDB profile photo path (null when TMDB has no photo). */
data class CastMember(val name: String, val profilePath: String? = null)

/**
 * Serialization for the cached `castJson` column.
 *
 * The column used to hold a plain array of names, `["Tom Hanks","Robin Wright"]`. It now holds objects
 * carrying the profile photo path too, `[{"n":"Tom Hanks","p":"/abc.jpg"}]`.
 *
 * **This is a content change, not a schema change** — `castJson` is already a TEXT column, so there is
 * no Room migration and no database version bump.
 *
 * [parse] deliberately reads BOTH shapes. Existing installs have name-only rows cached for up to the
 * positive TTL, and without the old-format branch every one of those users would see an empty cast list
 * until their cache expired. Old rows simply come back with no photo.
 *
 * Capturing the photo path costs no extra network call: `credits` is already part of the
 * `append_to_response` on the details request, so the profile path is in a payload the app already
 * downloads — it was previously parsed and thrown away.
 *
 * Version 2 (Mayo TV, 7 Nov 2026) adds the director (a series' creators) in the same column, as entries
 * with `"r":"d"`, after a leading `{"v":2}` marker — again no migration. Rows without the marker are
 * re-fetched once ([isLegacyFormat]), so titles cached before learn their director too.
 */
object MetadataCast {

    private const val KEY_NAME = "n"
    private const val KEY_PROFILE = "p"
    private const val KEY_ROLE = "r"
    private const val ROLE_DIRECTOR = "d"
    private const val KEY_VERSION = "v"
    private const val VERSION = 2

    fun parse(json: String?): List<CastMember> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<CastMember>(arr.length())
            for (i in 0 until arr.length()) {
                // New format: an object. Old format: a bare name string.
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    // The version marker and the directors are not cast.
                    if (obj.has(KEY_VERSION) || obj.has(KEY_ROLE)) continue
                    val name = obj.optString(KEY_NAME).takeIf { it.isNotBlank() } ?: continue
                    val path = obj.optString(KEY_PROFILE).takeIf { it.isNotBlank() && it != "null" }
                    out += CastMember(name, path)
                } else {
                    val name = arr.optString(i).takeIf { it.isNotBlank() } ?: continue
                    out += CastMember(name)
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    fun serialize(cast: List<CastMember>, directors: List<CastMember> = emptyList()): String = JSONArray().apply {
        put(JSONObject().put(KEY_VERSION, VERSION))
        fun add(member: CastMember, role: String?) = put(
            JSONObject()
                .put(KEY_NAME, member.name)
                .apply { member.profilePath?.let { put(KEY_PROFILE, it) } }
                .apply { role?.let { put(KEY_ROLE, it) } },
        )
        cast.forEach { add(it, null) }
        directors.forEach { add(it, ROLE_DIRECTOR) }
    }.toString()

    /** The director(s), or a series' creators; empty in rows written before version 2. */
    fun directors(json: String?): List<CastMember> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i)?.takeIf { it.optString(KEY_ROLE) == ROLE_DIRECTOR } ?: return@mapNotNull null
                val name = obj.optString(KEY_NAME).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CastMember(name, obj.optString(KEY_PROFILE).takeIf { it.isNotBlank() && it != "null" })
            }
        }.getOrDefault(emptyList())
    }

    /**
     * True when [json] is a cast list written by an older format: the name-only one, or one without
     * the version-2 marker (no directors).
     *
     * Those rows can never show photos, and with the long positive TTL they would sit in the cache for
     * months, so the repository treats them as stale and re-fetches that title the next time it is
     * opened. Lazy on purpose: it spreads the refresh across normal browsing instead of firing a
     * download wave the moment the app updates.
     *
     * An empty or absent list is NOT legacy — there is simply no cast, and re-fetching forever would
     * be a loop.
     */
    fun isLegacyFormat(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return runCatching {
            val arr = JSONArray(json)
            if (arr.length() == 0) return false
            // Current format leads with the version marker; the oldest is an array of bare strings.
            val first = arr.optJSONObject(0) ?: return true
            first.optInt(KEY_VERSION, 0) < VERSION
        }.getOrDefault(false)
    }

    /** Just the names, for the compact one-line cast rows in the browse side panes. */
    fun names(json: String?): List<String> = parse(json).map { it.name }
}
