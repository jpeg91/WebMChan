package app.webmchan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks of the ffmpeg filter graph that joins a video's pieces and puts a picked thumbnail frame in
 * front of it (Converter.joinGraph). Plain JVM tests: no phone needed (./gradlew test).
 */
class ThumbnailGraphTest {
    private val two = listOf(EditSlice(0.0, 5.0), EditSlice(20.0, 4.0))
    private val one = listOf(EditSlice(0.0, 5.0))

    /** Without a thumbnail the graph is what it always was (cuts and joins only). */
    @Test fun noThumbnailIsTheJoinAlone() {
        assertEquals(
            "[0:v:0]setpts=PTS-STARTPTS[v0];[0:a:0]asetpts=PTS-STARTPTS,afade=t=out:st=4.990:d=0.01[a0];" +
                "[1:v:0]setpts=PTS-STARTPTS[v1];[1:a:0]asetpts=PTS-STARTPTS,afade=t=in:st=0:d=0.01[a1];" +
                "[v0][a0][v1][a1]concat=n=2:v=1:a=1[cv][aout];[cv]fps=30[vout]",
            Converter.joinGraph(two, "fps=30", true),
        )
    }

    /** One piece, with sound: the frame is the next input (1), shown for one frame, sound delayed to match. */
    @Test fun thumbnailInFrontOfOnePiece() {
        val g = Converter.joinGraph(one, "fps=30,scale=-2:360", true, Converter.ThumbFrame(1, 1.0 / 30.0, "scale=-2:360"))
        assertEquals(
            "[0:v:0]setpts=PTS-STARTPTS[v0];[0:a:0]asetpts=PTS-STARTPTS[a0];" +
                "[v0][a0]concat=n=1:v=1:a=1[cv][ca];" +
                "[cv]fps=30,scale=-2:360[fv];" +
                "[1:v:0]trim=end_frame=1,setpts=PTS-STARTPTS,scale=-2:360[tv];" +
                "[fv]tpad=start_duration=0.0333:start_mode=clone[pv];" +
                "[pv][tv]overlay=x=0:y=0:eof_action=repeat:enable='lt(t,0.0333)'[vout];" +
                "[ca]adelay=delays=33.3:all=1[aout]",
            g,
        )
    }

    /** No sound: nothing is delayed and there is no audio output. */
    @Test fun thumbnailWithoutSound() {
        val g = Converter.joinGraph(one, "null", false, Converter.ThumbFrame(1, 1.0 / 30.0, "null"))
        assertEquals(
            "[0:v:0]setpts=PTS-STARTPTS[v0];[v0]concat=n=1:v=1:a=0[cv];" +
                "[cv]null[fv];" +
                "[1:v:0]trim=end_frame=1,setpts=PTS-STARTPTS,null[tv];" +
                "[fv]tpad=start_duration=0.0333:start_mode=clone[pv];" +
                "[pv][tv]overlay=x=0:y=0:eof_action=repeat:enable='lt(t,0.0333)'[vout]",
            g,
        )
        assertFalse(g.contains("aout"))
    }

    /** Joined pieces: the frame's input comes after all of them, and the fades at the join are kept. */
    @Test fun thumbnailAfterJoinedPieces() {
        val g = Converter.joinGraph(two, "null", true, Converter.ThumbFrame(2, 1.0 / 60.0, "null"))
        assertTrue(g.contains("[v0][a0][v1][a1]concat=n=2:v=1:a=1[cv][ca];"))
        assertTrue(g.contains("afade=t=out:st=4.990:d=0.01"))
        assertTrue(g.contains("[2:v:0]trim=end_frame=1"))
        // One frame at 60 fps.
        assertTrue(g.contains("tpad=start_duration=0.0167:start_mode=clone"))
        assertTrue(g.endsWith("[ca]adelay=delays=16.7:all=1[aout]"))
    }
}
