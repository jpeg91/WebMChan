// #battle
package app.webmchan.battle

import java.util.Locale

/**
 * What the app remembers about a conversion beyond what the finished file shows, saved on its
 * History card (see QueueItem.facts). Without it the stats that depend on the SOURCE video (type, HP's
 * squeeze, Speed) can't be worked out, because a History card describes the converted file.
 *
 * Cards made before this existed have none, and get middle-band stats instead (see [Missing]).
 */
data class ConversionFacts(
    /**
     * One id shared by every part of a split video (the queue card's id), so the parts fold into
     * a single Battle-Card. 0 = not known.
     */
    val groupId: Long = 0L,
    /** Size of the source file in bytes; 0 when not known. */
    val sourceBytes: Long = 0L,
    /** The source's container as the probe named it ("MP4", "AVI", "MP3"...); empty when not known. */
    val sourceContainer: String = "",
    /** How long this file's conversion took, in milliseconds; 0 when not known. */
    val convMillis: Long = 0L,
)

/** One converted file (one part, for a split video), as far as stats are concerned. */
data class PartInput(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    /** The output's frame rate; 0 when not known. A sound file's picture is a still, so this is low there. */
    val fps: Double,
    val outBytes: Long,
    /** The output video stream's bitrate in kbps; 0 when not known. */
    val videoKbps: Int,
    /** The size limit the file was made to fit; 0 when not known. */
    val limitBytes: Long,
    /** 0 when not known. */
    val convMillis: Long,
)

/** Everything a card is made from. One per video, however many parts it was split into. */
data class CardInput(
    /** The History card's id (the first part's, for a split video). */
    val id: Long,
    val memberIds: List<Long>,
    /** The saved file's name (the first part's). */
    val sourceName: String,
    val isAudio: Boolean,
    /** The probe's name for the source container; empty when not known. */
    val sourceContainer: String,
    /** A guess at the source's format from its file name, used only when [sourceContainer] is empty. */
    val extensionHint: String,
    /** The source file's size in bytes; 0 when not known. */
    val sourceBytes: Long,
    val parts: List<PartInput>,
)

/** Turns a [CardInput] into a [BattleCard]. Deterministic: the same input always makes the same card. */
object BattleDerive {

    fun derive(input: CardInput): BattleCard {
        require(input.parts.isNotEmpty()) { "A card needs at least one part" }
        val parts = input.parts
        val missing = LinkedHashSet<Missing>()

        val totalSec = parts.sumOf { it.durationSec.coerceAtLeast(0.0) }
        val totalOut = parts.sumOf { it.outBytes.coerceAtLeast(0L) }

        // HP: how long the video is and how hard it was squeezed.
        val ratio = if (input.sourceBytes > 0L && totalOut > 0L) input.sourceBytes.toDouble() / totalOut else null
        if (ratio == null) missing.add(Missing.SOURCE_SIZE)

        // Attack: the picture size, averaged over the parts that have one.
        val sizes = parts.map { it.width.toDouble() * it.height }.filter { it > 0.0 }
        val pixels = if (sizes.isEmpty()) 0.0 else sizes.average()

        // Special Attack: the bitrate. A sound file's picture is a still, so its own bitrate means
        // nothing there; the whole file's does.
        val overallKbps = if (totalSec > 0.0) totalOut * 8.0 / 1000.0 / totalSec else 0.0
        val streamKbps = parts.map { it.videoKbps }.filter { it > 0 }
        val kbps = if (!input.isAudio && streamKbps.isNotEmpty()) streamKbps.average() else overallKbps

        // Defence: the tightest squeeze against the size limit of any part.
        val fills = parts.filter { it.limitBytes > 0L && it.outBytes > 0L }.map { it.outBytes.toDouble() / it.limitBytes }
        val fill = fills.maxOrNull()
        if (fill == null) missing.add(Missing.SIZE_LIMIT)

        // Special Defence: the frame rate, averaged over the parts that have one.
        val frameRates = parts.map { it.fps }.filter { it > 0.0 }
        val fps = if (frameRates.isEmpty()) 0.0 else frameRates.average()

        // Speed: video made per second of waiting, from the slowest part.
        val rates = parts.filter { it.convMillis > 0L && it.durationSec > 0.0 }.map { it.durationSec / (it.convMillis / 1000.0) }
        val rate = rates.minOrNull()
        if (rate == null) missing.add(Missing.CONVERSION_TIME)

        val type = typeFor(input.sourceContainer, input.extensionHint, input.isAudio)
        if (input.sourceContainer.isBlank() && input.extensionHint.isBlank()) missing.add(Missing.SOURCE_CONTAINER)

        val stats = BattleStats(
            hp = BattleBands.hp(totalSec, ratio),
            attack = BattleBands.attack(pixels),
            spAttack = BattleBands.spAttack(kbps),
            defence = BattleBands.defence(fill),
            spDefence = BattleBands.spDefence(fps),
            speed = BattleBands.speed(rate),
        )

        // The core seed comes from what the card IS, so identical conversions share it. The personal
        // seed adds the History card's id, so they still differ a little.
        val coreSeed = BattleHash.of(
            "core|${type.name}|${stats.hp}|${stats.attack}|${stats.spAttack}|${stats.defence}|${stats.spDefence}|${stats.speed}|${input.isAudio}"
        )
        val personalSeed = BattleHash.mix(coreSeed, input.id)

        return BattleCard(
            id = input.id,
            memberIds = input.memberIds,
            name = displayName(input.sourceName).ifBlank { nameFor(type, stats, coreSeed, personalSeed) },
            sourceName = input.sourceName,
            type = type,
            stats = stats,
            moves = BattleMoves.pick(type, coreSeed, personalSeed),
            isAudio = input.isAudio,
            parts = parts.size,
            missing = missing,
        )
    }

    // -- Type ---------------------------------------------------------------------------------

    private val mainstream = setOf("mp4", "mov", "m4v", "quicktime", "3gp", "3g2")
    private val matroska = setOf("matroska", "webm", "mkv", "mka")
    private val legacy = setOf(
        "avi", "wmv", "asf", "flv", "mpeg", "mpg", "mpegts", "ts", "m2ts", "mts", "vob", "ogv", "rm", "rmvb", "divx", "xvid",
    )
    private val wild = setOf("gif", "apng")
    private val audioOnly = setOf("mp3", "flac", "ogg", "oga", "opus", "wav", "aac", "wma", "aiff", "aif", "m4a", "amr")

    /**
     * The type for a source. The probe's [container] name is used when there is one, otherwise a
     * guess from the file's extension ([extensionHint]). Anything not recognised is [CardType.WILD],
     * except a sound card, which falls back to [CardType.AUDIO].
     */
    fun typeFor(container: String, extensionHint: String, isAudio: Boolean): CardType {
        val text = container.ifBlank { extensionHint }
        val tokens = text.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        return when {
            tokens.any { it in mainstream } -> CardType.MAINSTREAM
            tokens.any { it in matroska } -> CardType.MATROSKA
            tokens.any { it in legacy } -> CardType.LEGACY
            tokens.any { it in wild } -> CardType.WILD
            tokens.any { it in audioOnly } -> CardType.AUDIO
            isAudio -> CardType.AUDIO
            else -> CardType.WILD
        }
    }

    /**
     * The file extension of [name] in lower case ("clip.MP4" gives "mp4"), or empty if there isn't a
     * sensible one. Used to guess a source's format when the probe's own name for it wasn't recorded.
     */
    fun extensionOf(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        return if (ext.length in 2..5 && ext.all { it in 'a'..'z' || it in '0'..'9' }) ext else ""
    }

    // -- Name ---------------------------------------------------------------------------------

    private val prefixes: Map<CardType, List<String>> = mapOf(
        CardType.MAINSTREAM to listOf("Pix", "Frame", "Reel", "Clip", "Vid", "Mux"),
        CardType.MATROSKA to listOf("Clus", "Seg", "Nest", "Stack", "Layer", "Chap"),
        CardType.LEGACY to listOf("Retro", "Dust", "Tape", "Scan", "Crackle", "Mono"),
        CardType.AUDIO to listOf("Echo", "Wave", "Tone", "Bass", "Hiss", "Chime"),
        CardType.WILD to listOf("Loop", "Glitch", "Dither", "Flick", "Blip", "Smear"),
    )
    private val suffixLow = listOf("ling", "let", "bit", "pup", "nub")
    private val suffixMid = listOf("ox", "ard", "wing", "beast", "drone")
    private val suffixHigh = listOf("saur", "titan", "lord", "wyrm", "king")

    /**
     * Gives every card in [cards] a different name, in the order given: the first keeps its name and later
     * repeats get "(2)", "(3)" ... (skipping any name already taken). A fight finds its cards by name, so a
     * party must not have two of the same. Order and everything else about the cards is unchanged.
     */
    fun uniqueNames(cards: List<BattleCard>): List<BattleCard> {
        val used = HashSet<String>()
        return cards.map { c ->
            if (used.add(c.name)) c
            else {
                var n = 2
                while (!used.add("${c.name} ($n)")) n++
                c.copy(name = "${c.name} ($n)")
            }
        }
    }

    /** Two conversions of the same file share a name; the older keeps it and later ones get "(2)", "(3)" ... */
    fun numberRepeats(cards: List<BattleCard>): List<BattleCard> {
        val seen = HashMap<String, Int>()
        val renamed = HashMap<Long, String>()
        for (c in cards.sortedBy { it.id }) {
            val n = (seen[c.name] ?: 0) + 1
            seen[c.name] = n
            if (n > 1) renamed[c.id] = "${c.name} ($n)"
        }
        return cards.map { c -> renamed[c.id]?.let { c.copy(name = it) } ?: c }
    }

    /** The card for a failed conversion: every stat at the bottom band, and a single move that does nothing. */
    fun dud(id: Long, sourceName: String): BattleCard = BattleCard(
        id = id,
        memberIds = listOf(id),
        name = displayName(sourceName).ifBlank { "Dud" },
        sourceName = sourceName,
        type = CardType.WILD,
        stats = BattleStats(1, 1, 1, 1, 1, 1),
        moves = listOf(BattleMoves.dudMove),
        isAudio = false,
        parts = 1,
        missing = emptySet(),
        isDud = true,
    )

    private const val MAX_NAME = 36
    private val tagSuffix = Regex("-WebMChan_for_Android$")
    private val markerSuffix = Regex("-[AM][BFSQ]?$")
    private val partSuffix = Regex("-Part\\d+$")

    /**
     * The name a card shows: the converted file's name without its extension and without what the app
     * added ("-Part02", "-MQ", "-WebMChan_for_Android"), so "clip-Part02-MQ-WebMChan_for_Android.webm"
     * is "clip". Long names are cut. Empty if nothing is left.
     */
    fun displayName(fileName: String): String {
        var n = fileName.trim().substringBeforeLast('.', fileName.trim())
        n = tagSuffix.replace(n, "")
        n = markerSuffix.replace(n, "")
        n = partSuffix.replace(n, "")
        n = n.trim()
        return if (n.length > MAX_NAME) n.take(MAX_NAME - 1).trimEnd() + "…" else n
    }

    /**
     * An invented creature name: a prefix for the type and a suffix for how strong the card is. It
     * comes from the personal seed, so copies of one conversion have different names.
     */
    fun nameFor(type: CardType, stats: BattleStats, coreSeed: Long, personalSeed: Long): String {
        val rng = BattleRng(personalSeed)
        val front = prefixes[type] ?: prefixes.getValue(CardType.WILD)
        val power = stats.power
        val back = when {
            power <= 19 -> suffixLow
            power <= 34 -> suffixMid
            else -> suffixHigh
        }
        // The core seed picks the prefix, so cousins share a family name; only the ending varies.
        val prefix = front[BattleRng(coreSeed).nextInt(front.size)]
        return prefix + rng.pick(back)
    }
}
