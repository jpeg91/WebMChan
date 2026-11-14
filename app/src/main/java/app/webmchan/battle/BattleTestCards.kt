// #battle
package app.webmchan.battle

import java.util.Random

/**
 * Made-up cards for testing, so the game can be tried without first converting a pile of videos.
 * They are added to the roster while the "Battle-Cards test fighters" switch in Settings is on (BattleHost
 * reads it; delete this file and those lines before a real release). They have negative ids so they can never clash
 * with a real History card, and no thumbnail of their own (they show the app icon, see CardThumb).
 */
object BattleTestCards {
    private const val COUNT = 40

    fun cards(): List<BattleCard> {
        val rnd = Random(2024)
        val containers = listOf("MP4", "MP4", "MP4", "MP4", "Matroska/WebM", "Matroska/WebM", "AVI", "FLV", "GIF", "MOV")
        val rungs = listOf(240 to 426, 360 to 640, 480 to 854, 540 to 960, 720 to 1280, 1080 to 1920)
        return (1..COUNT).map { n ->
            val id = -n.toLong()
            val audio = n % 10 == 0
            val sec = (3 + Math.exp(rnd.nextDouble() * Math.log(115.0))).coerceAtMost(120.0)
            val limit = if (rnd.nextInt(4) == 0) 4_000_000L else 6_000_000L
            val fill = if (rnd.nextInt(3) != 0) 0.88 + rnd.nextDouble() * 0.115 else 0.05 + rnd.nextDouble() * 0.5
            val bytes = (limit * fill).toLong()
            val rung = rungs[minOf(5, maxOf(0, (Math.log(bytes * 8.0 / sec / 1000.0 / 80.0) / Math.log(2.0)).toInt() - 1 + rnd.nextInt(2)))]
            val fps = if (audio) 1.0 else listOf(24.0, 25.0, 30.0, 30.0, 30.0, 60.0, 15.0, 10.0)[rnd.nextInt(8)]
            val srcBytes = (bytes * (2 + Math.exp(rnd.nextDouble() * Math.log(60.0)))).toLong()
            val rtf = 0.03 + rnd.nextDouble() * rnd.nextDouble() * 3.0
            BattleDerive.derive(
                CardInput(
                    id, listOf(id), "Test clip ${n.toString().padStart(2, '0')}.webm", audio,
                    if (audio) "MP3" else containers[rnd.nextInt(containers.size)], "", srcBytes,
                    listOf(PartInput(sec, rung.second, rung.first, fps, bytes, (bytes * 8.0 / sec / 1000.0).toInt(), limit, (sec / rtf * 1000).toLong())),
                ),
            )
        }
    }
}
