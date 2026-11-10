package tv.own.owntv.core.live

import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity

/** The guide key every read of a channel's programmes uses. */
object GuideKeys {
    /**
     * Set by the app: a key to read [channel]'s guide under instead of its own `epgChannelId`, or null to
     * leave the channel as it is. Called on every guide read, for whole lists of channels, from any thread:
     * it must be fast, must not block and must not throw (a throw is swallowed and the channel falls back
     * to its own id). The cached now/next in `LiveEpgReader` is keyed by channel, so the app calls
     * `LiveEpgReader.clearCache()` after changing what the override answers.
     */
    @Volatile
    var override: ((ChannelEntity) -> String?)? = null

    /** A manual match wins, then the app's [override], then the channel's own id; trimmed, lowercased, null when empty. */
    fun keyOf(channel: ChannelEntity, cust: SectionCustomizations): String? =
        clean(cust.epgMatchResolver.epgIdFor(channel))
            ?: clean(override?.let { fn -> runCatching { fn(channel) }.getOrNull() })
            ?: clean(channel.epgChannelId)

    private fun clean(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
