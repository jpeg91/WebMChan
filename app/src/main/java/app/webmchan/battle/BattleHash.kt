// #battle
package app.webmchan.battle

/**
 * Stable hashing and a small seeded random source for Battle-Cards. Everything here is plain
 * arithmetic with fixed constants, so the same input gives the same answer on every phone and every
 * run (unlike anything that leans on java.util.Random or an object's identity). A card's moves and
 * name come from these, so a card never changes between launches.
 */
object BattleHash {
    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
    private const val MIX_A = -4658895280553007687L // 0xBF58476D1CE4E5B9
    private const val MIX_B = -7723592293110705685L // 0x94D049BB133111EB
    internal const val GOLDEN = -7046029254386353131L // 0x9E3779B97F4A7C15

    /** A well-spread 64-bit hash of [text] (FNV-1a over its UTF-8 bytes, then a final scramble). */
    fun of(text: String): Long {
        var h = FNV_OFFSET
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xff)) * FNV_PRIME
        }
        return scramble(h)
    }

    /** Two hashes folded into one; the order matters. */
    fun mix(a: Long, b: Long): Long = scramble(a xor (scramble(b) + GOLDEN + (a shl 6) + (a ushr 2)))

    /** The SplitMix64 finaliser: turns similar inputs into unrelated-looking outputs. */
    internal fun scramble(value: Long): Long {
        var z = value
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return z xor (z ushr 31)
    }
}

/** A tiny seeded random source (SplitMix64). The same seed always gives the same run of numbers. */
class BattleRng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += BattleHash.GOLDEN
        return BattleHash.scramble(state)
    }

    /** 0 until [bound]. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** One of [items], picked evenly. */
    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]
}
