package com.cadence.activity.domain;

/**
 * Where a playback started. Spec 4 names PLAYLIST, ALBUM, SEARCH and RADIO; the web client also starts plays from
 * artist pages, the user's library (liked songs, home shelves) and other places (DECISIONS.md D70).
 */
public enum PlaySource {
    PLAYLIST,
    ALBUM,
    SEARCH,
    RADIO,
    ARTIST,
    LIBRARY,
    OTHER
}
