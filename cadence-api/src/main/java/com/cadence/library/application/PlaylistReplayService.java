package com.cadence.library.application;

import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.library.PlaylistReplay;
import com.cadence.library.application.LibraryViews.PlaylistView;
import com.cadence.library.domain.Playlist;
import com.cadence.library.domain.Visibility;
import com.cadence.library.infrastructure.PlaylistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
class PlaylistReplayService implements PlaylistReplay {

    private final PlaylistRepository playlists;
    private final LibraryMapper mapper;
    private final LibraryEvents events;
    private final Clock clock;

    PlaylistReplayService(PlaylistRepository playlists, LibraryMapper mapper, LibraryEvents events, Clock clock) {
        this.playlists = playlists;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int replayPublicPlaylists() {
        int count = 0;
        for (Playlist playlist : playlists.findByVisibility(Visibility.PUBLIC)) {
            PlaylistView view = mapper.toView(playlist);
            events.playlistChanged(view, view.id(), Action.UPDATED, clock.instant());
            count++;
        }
        return count;
    }
}
