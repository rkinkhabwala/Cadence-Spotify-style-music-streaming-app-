package com.cadence.library;

/** Republishes library state as events, e.g. when the search index is rebuilt. */
public interface PlaylistReplay {

    /**
     * Writes a {@code library.playlist-changed} UPDATED event for every PUBLIC playlist to the outbox.
     *
     * @return the number of events written
     */
    int replayPublicPlaylists();
}
