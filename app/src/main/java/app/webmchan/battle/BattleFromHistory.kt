// #battle
package app.webmchan.battle

import app.webmchan.ItemStatus
import app.webmchan.QueueItem

/**
 * Builds the Battle-Cards roster out of History: the only place the game touches the app's own
 * cards. Everything it needs is on the finished card (what the converted file is, what it was made
 * for) plus its [ConversionFacts]. A card made before the facts were recorded still works; the
 * stats it can't work out sit in the middle band and are marked on the card.
 */
object BattleFromHistory {

    /**
     * One card per video, newest first. [history] is in the order the app keeps it (oldest first).
     * The parts of a split video share a group id and fold into one card; a card without one stands alone.
     */
    fun roster(history: List<QueueItem>): List<BattleCard> {
        val groups = LinkedHashMap<String, MutableList<QueueItem>>()
        for (card in history) {
            if (card.status != ItemStatus.DONE || card.info == null) continue
            val group = card.facts?.groupId?.takeIf { it != 0L }
            val key = if (group != null) "g$group" else "c${card.id}"
            groups.getOrPut(key) { ArrayList() }.add(card)
        }
        val cards = groups.values.map { BattleDerive.derive(inputFor(it.sortedBy { c -> c.id })) }
        return BattleDerive.numberRepeats(cards).sortedByDescending { it.id }
    }

    /** The input for one video: [members] are its History cards, in part order. */
    internal fun inputFor(members: List<QueueItem>): CardInput {
        val first = members.first()
        val facts = first.facts
        return CardInput(
            id = first.id,
            memberIds = members.map { it.id },
            sourceName = first.name,
            isAudio = first.isAudio,
            sourceContainer = facts?.sourceContainer.orEmpty(),
            extensionHint = BattleDerive.extensionOf(first.uri.lastPathSegment),
            sourceBytes = facts?.sourceBytes ?: 0L,
            parts = members.map { partOf(it) },
        )
    }

    private fun partOf(card: QueueItem): PartInput {
        val info = card.info!! // roster() only lets cards with info through
        return PartInput(
            durationSec = info.durationSec,
            width = info.width,
            height = info.height,
            // A frame rate that couldn't be read is a placeholder; the card then holds the rate aimed for.
            fps = if (info.fpsReadable) info.fps else info.targetedFps,
            outBytes = info.fileBytes,
            videoKbps = info.videoKbps,
            limitBytes = card.settings?.limitBytes ?: 0L,
            convMillis = card.facts?.convMillis ?: 0L,
        )
    }
}
