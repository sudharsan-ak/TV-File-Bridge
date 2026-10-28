package com.tvfilebridge.app.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Exercises AppLabelGuesser - a pure language port with no intended behavior change. */
public class AppLabelGuesserTest {

    @Test
    public void guessLabel_simplePackage() {
        // Last segment "ninja" isn't a known variant word or TV suffix, so it
        // passes through unchanged apart from capitalization.
        assertEquals("Ninja", AppLabelGuesser.guessLabel("com.netflix.ninja"));
    }

    @Test
    public void guessLabel_stripsKnownTvSuffix() {
        // Last segment "androidtv" isn't a variant word, so it's kept as
        // nameSegment - but KNOWN_SUFFIXES then strips "tv" off the end of it
        // (processed after "androidtv" itself, which only matches when
        // strictly shorter than the segment, not equal), leaving "android".
        assertEquals("Android", AppLabelGuesser.guessLabel("com.disney.disneyplus.androidtv"));
    }

    @Test
    public void guessLabel_skipsVariantSegment() {
        assertEquals("Smarttube", AppLabelGuesser.guessLabel("org.smarttube.stable"));
    }

    @Test
    public void guessLabel_underscoresAndHyphensBecomeSpacesAndCapitalized() {
        // KNOWN_SUFFIXES strips "app" off the end of "my_cool-app" BEFORE the
        // underscore/hyphen split happens, so "app" never survives as its own word.
        assertEquals("My Cool", AppLabelGuesser.guessLabel("com.example.my_cool-app"));
    }
}
