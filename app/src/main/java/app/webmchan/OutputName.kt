package app.webmchan

/**
 * How a converted file is named. Plain Kotlin with nothing from Android in it, so the rule is checked
 * by unit tests (OutputNameTest) without a phone.
 *
 * The name is the base (the source video's name, or the one typed on the card), then "-Part01" for a
 * split video's parts, then a marker for the sound ("A" kept, "M" muted) and one for the encoding
 * effort, then the app tag: "clip-Part02-MQ-WebMChan_for_Android.webm".
 */
object OutputName {
    /** Added to the end of every converted file's name, just before ".webm". */
    const val TAG = "WebMChan_for_Android"

    /**
     * "Part1" ... "Part9", or zero-padded to the width of the part count ("Part01" ... "Part12")
     * so the files sort in order.
     */
    fun partName(part: Int, numParts: Int): String =
        "Part" + part.toString().padStart(numParts.toString().length, '0')

    /** "B" Best quality, "F" Fastest, "S" Speed, "Q" Quality. */
    fun effortMarker(bestQuality: Boolean, fastest: Boolean, fastEncode: Boolean): String =
        if (bestQuality) "B" else if (fastest) "F" else if (fastEncode) "S" else "Q"

    /** The part of the name before everything added on: the typed name if there is one, else the source's name without its extension. */
    fun baseFor(sourceName: String, customName: String?): String =
        customName ?: sourceName.substringBeforeLast('.', sourceName)

    /** The complete file name, ending in ".webm". [part] is 1-based and only matters when [numParts] is above 1. */
    fun build(base: String, part: Int, numParts: Int, audioIncluded: Boolean, effort: String): String {
        val partSuffix = if (numParts > 1) "-${partName(part, numParts)}" else ""
        val audioMarker = if (audioIncluded) "A" else "M"
        return "$base$partSuffix-$audioMarker$effort-$TAG.webm"
    }
}
