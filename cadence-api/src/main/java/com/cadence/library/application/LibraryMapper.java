package com.cadence.library.application;

import com.cadence.library.application.LibraryViews.PlaylistView;
import com.cadence.library.domain.Playlist;
import org.mapstruct.Mapper;

@Mapper
interface LibraryMapper {

    PlaylistView toView(Playlist playlist);
}
