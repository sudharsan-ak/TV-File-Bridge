package com.tvfilebridge.app.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Exercises NowPlayingParser against real-device sample strings (captured
 * live from a Sony Bravia) - a pure language port with no intended behavior
 * change, so these samples confirm that held.
 */
public class NowPlayingParserTest {

    @Test
    public void parse_playingWithMetadata() {
        String dump = "Sessions Stack - have 1 sessions\n"
                + "  package=com.fox.foxone\n"
                + "    state=PlaybackState {state=3, position=12000, buffered position=-1, speed=1.0}\n"
                + "    metadata: size=3, description=Video Title, Channel - 538K views, mediaId=null\n";

        NowPlayingInfo info = NowPlayingParser.parse(dump);

        assertNotNull(info);
        assertEquals("com.fox.foxone", info.getPackageName());
        assertEquals("Foxone", info.getAppLabel());
        assertTrue(info.isPlaying());
        assertFalse(info.isPaused());
        assertEquals(Long.valueOf(12000), info.getPositionMs());
        assertEquals("Video Title", info.getTitle());
        assertEquals("Channel - 538K views", info.getArtist());
    }

    @Test
    public void parse_nullMetadataAndNegativePosition() {
        String dump = "package=com.fox.foxone\n"
                + "state=PlaybackState {state=3, position=-1, buffered position=-1, ...}\n"
                + "metadata: null\n";

        NowPlayingInfo info = NowPlayingParser.parse(dump);

        assertNotNull(info);
        assertTrue(info.isPlaying());
        assertNull(info.getPositionMs());
        assertNull(info.getTitle());
        assertNull(info.getArtist());
        assertNull(info.getDurationMs());
    }

    @Test
    public void parse_pausedState() {
        String dump = "package=com.example.app\nstate=PlaybackState {state=2, position=500, ...}\n";
        NowPlayingInfo info = NowPlayingParser.parse(dump);
        assertNotNull(info);
        assertFalse(info.isPlaying());
        assertTrue(info.isPaused());
    }

    @Test
    public void parse_noPackageLine_returnsNull() {
        assertNull(NowPlayingParser.parse("Sessions Stack - have 0 sessions\n"));
    }
}
