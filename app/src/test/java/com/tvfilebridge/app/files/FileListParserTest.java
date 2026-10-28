package com.tvfilebridge.app.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Exercises FileListParser against real toybox `ls -la` / `find -printf`
 * output shapes - a pure language port with no intended behavior change.
 */
public class FileListParserTest {

    @Test
    public void parseLsLine_regularFile() {
        String line = "-rw-rw---- 1 root sdcard_rw 4832 2026-10-20 14:03 notes.txt";
        RemoteFile file = FileListParser.parseLsLine(line, "/sdcard");

        assertNotNull(file);
        assertEquals("notes.txt", file.getName());
        assertEquals("/sdcard/notes.txt", file.getPath());
        assertFalse(file.isDirectory());
        assertEquals(4832, file.getSizeBytes());
        assertNotNull(file.getModifiedAt());
    }

    @Test
    public void parseLsLine_directory() {
        String line = "drwxrwx--x 3 root sdcard_rw 3488 2026-09-01 09:30 Download";
        RemoteFile file = FileListParser.parseLsLine(line, "/sdcard/");

        assertNotNull(file);
        assertEquals("Download", file.getName());
        assertEquals("/sdcard/Download", file.getPath());
        assertTrue(file.isDirectory());
    }

    @Test
    public void parseLsLine_fileNameWithSpaces() {
        String line = "-rw-rw---- 1 root sdcard_rw 100 2026-10-20 14:03 My Vacation Photo.jpg";
        RemoteFile file = FileListParser.parseLsLine(line, "/sdcard");

        assertNotNull(file);
        assertEquals("My Vacation Photo.jpg", file.getName());
    }

    @Test
    public void parseLsLine_symlink_keepsLinkNameOnly() {
        String line = "lrwxrwxrwx 1 root root 21 2026-09-01 09:30 primary -> /storage/self/primary";
        RemoteFile file = FileListParser.parseLsLine(line, "/storage/self");

        assertNotNull(file);
        assertEquals("primary", file.getName());
    }

    @Test
    public void parseLsLine_totalLine_returnsNull() {
        assertNull(FileListParser.parseLsLine("total 48", "/sdcard"));
    }

    @Test
    public void parseLsLine_blankLine_returnsNull() {
        assertNull(FileListParser.parseLsLine("", "/sdcard"));
    }

    @Test
    public void parseLsLine_dotAndDotDot_returnsNull() {
        assertNull(FileListParser.parseLsLine("drwxrwx--x 3 root sdcard_rw 3488 2026-09-01 09:30 .", "/sdcard"));
        assertNull(FileListParser.parseLsLine("drwxrwx--x 3 root sdcard_rw 3488 2026-09-01 09:30 ..", "/sdcard"));
    }

    @Test
    public void parseLsLine_tooFewColumns_returnsNull() {
        assertNull(FileListParser.parseLsLine("not enough columns here", "/sdcard"));
    }

    @Test
    public void parseFindLine_regularEntry() {
        String line = "4832 1761004980.5000000000 /sdcard/Download/notes.txt";
        RemoteFile file = FileListParser.parseFindLine(line);

        assertNotNull(file);
        assertEquals("notes.txt", file.getName());
        assertEquals("/sdcard/Download/notes.txt", file.getPath());
        assertFalse(file.isDirectory());
        assertEquals(4832, file.getSizeBytes());
        assertEquals(Long.valueOf(1761004980000L), file.getModifiedAt());
    }

    @Test
    public void parseFindLine_tooFewColumns_returnsNull() {
        assertNull(FileListParser.parseFindLine("4832 1761004980.5"));
    }
}
