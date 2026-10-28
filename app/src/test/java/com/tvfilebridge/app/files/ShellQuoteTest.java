package com.tvfilebridge.app.files;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShellQuoteTest {

    @Test
    public void quote_plainPath() {
        assertEquals("'/sdcard/notes.txt'", ShellQuote.quote("/sdcard/notes.txt"));
    }

    @Test
    public void quote_pathWithSingleQuote() {
        assertEquals("'/sdcard/it'\\''s mine.txt'", ShellQuote.quote("/sdcard/it's mine.txt"));
    }

    @Test
    public void quote_pathWithSpaces() {
        assertEquals("'/sdcard/My Vacation Photo.jpg'", ShellQuote.quote("/sdcard/My Vacation Photo.jpg"));
    }
}
