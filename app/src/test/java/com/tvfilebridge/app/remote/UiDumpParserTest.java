package com.tvfilebridge.app.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import kotlin.Pair;
import org.junit.Test;

/**
 * Exercises UiDumpParser against real-device sample shapes (captured live
 * from SmartTube's uiautomator dump) - a pure language port with no intended
 * behavior change, so these samples confirm that held.
 */
public class UiDumpParserTest {

    @Test
    public void parseTimePair_smartTubeShape() {
        String xml = "<node text=\"3:55\"/><node text=\"/\"/><node text=\"15:22\"/>";
        Pair<Long, Long> pair = UiDumpParser.parseTimePair(xml);
        assertNotNull(pair);
        assertEquals(Long.valueOf(235000), pair.getFirst());
        assertEquals(Long.valueOf(922000), pair.getSecond());
    }

    @Test
    public void parseTimePair_hoursMinutesSeconds() {
        String xml = "<node text=\"1:23:45\"/><node text=\"2:00:00\"/>";
        Pair<Long, Long> pair = UiDumpParser.parseTimePair(xml);
        assertNotNull(pair);
        assertEquals(Long.valueOf((1L * 3600 + 23 * 60 + 45) * 1000), pair.getFirst());
        assertEquals(Long.valueOf(2L * 3600 * 1000), pair.getSecond());
    }

    @Test
    public void parseTimePair_totalNotGreaterThanCurrent_returnsNull() {
        String xml = "<node text=\"5:00\"/><node text=\"3:00\"/>";
        assertNull(UiDumpParser.parseTimePair(xml));
    }

    @Test
    public void parseTimePair_fewerThanTwoTimestamps_returnsNull() {
        assertNull(UiDumpParser.parseTimePair("<node text=\"3:55\"/>"));
    }

    @Test
    public void parseTimestamp_variants() {
        assertEquals(Long.valueOf(5000), UiDumpParser.parseTimestamp("5"));
        assertEquals(Long.valueOf(65000), UiDumpParser.parseTimestamp("1:05"));
        assertEquals(Long.valueOf((3600 + 2 * 60 + 3) * 1000L), UiDumpParser.parseTimestamp("1:02:03"));
        assertNull(UiDumpParser.parseTimestamp("1:02:03:04"));
    }

    @Test
    public void parseSeekBarBounds_smartTubeShape() {
        String xml = "<node class=\"android.widget.FrameLayout\" clickable=\"false\" bounds=\"[0,0][1920,1080]\"/>"
                + "<node class=\"com.liskovsoft.smartyoutubetv2.tv.ui.playback.controls.misc.SeekBar\" "
                + "clickable=\"true\" bounds=\"[112,842][1808,898]\"/>";

        SeekBarBounds bounds = UiDumpParser.parseSeekBarBounds(xml);

        assertNotNull(bounds);
        assertEquals(112, bounds.getLeft());
        assertEquals(842, bounds.getTop());
        assertEquals(1808, bounds.getRight());
        assertEquals(898, bounds.getBottom());
    }

    @Test
    public void parseSeekBarBounds_notClickable_returnsNull() {
        String xml = "<node class=\"...SeekBar\" clickable=\"false\" bounds=\"[0,0][100,100]\"/>";
        assertNull(UiDumpParser.parseSeekBarBounds(xml));
    }

    @Test
    public void parseTitleSubtitle_skipsTimestampsAndShortText() {
        String xml = "<node text=\"3:55\"/>"
                + "<node text=\"Remaining\"/>"
                + "<node text=\"Video Title Here\"/>"
                + "<node text=\"ab\"/>"
                + "<node text=\"Channel Name\"/>";

        Pair<String, String> result = UiDumpParser.parseTitleSubtitle(xml);

        assertNotNull(result);
        assertEquals("Video Title Here", result.getFirst());
        assertEquals("Channel Name", result.getSecond());
    }

    @Test
    public void parseTitleSubtitle_onlyOneCandidate_subtitleNull() {
        String xml = "<node text=\"Only One Title\"/>";
        Pair<String, String> result = UiDumpParser.parseTitleSubtitle(xml);
        assertNotNull(result);
        assertEquals("Only One Title", result.getFirst());
        assertNull(result.getSecond());
    }

    @Test
    public void parseTitleSubtitle_noCandidates_returnsNull() {
        assertNull(UiDumpParser.parseTitleSubtitle("<node text=\"3:55\"/>"));
    }

    @Test
    public void unescapeXml_numericAndNamedEntities() {
        assertEquals("👍", UiDumpParser.unescapeXml("&#128077;"));
        assertEquals("&", UiDumpParser.unescapeXml("&amp;"));
        assertEquals("<tag>", UiDumpParser.unescapeXml("&lt;tag&gt;"));
        assertEquals("a \"quoted\" value", UiDumpParser.unescapeXml("a &quot;quoted&quot; value"));
        assertEquals("it's", UiDumpParser.unescapeXml("it&apos;s"));
        assertEquals("plain text", UiDumpParser.unescapeXml("plain text"));
    }

    @Test
    public void unescapeXml_hexEntity() {
        assertEquals("A", UiDumpParser.unescapeXml("&#x41;"));
    }
}
