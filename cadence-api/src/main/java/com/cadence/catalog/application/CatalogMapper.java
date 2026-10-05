package com.cadence.catalog.application;

import com.cadence.catalog.CatalogRefs.ArtistRef;
import com.cadence.catalog.application.CatalogViews.AdminTrackView;
import com.cadence.catalog.application.CatalogViews.AlbumView;
import com.cadence.catalog.application.CatalogViews.ArtistView;
import com.cadence.catalog.application.CatalogViews.CreditView;
import com.cadence.catalog.application.CatalogViews.GenreView;
import com.cadence.catalog.domain.Album;
import com.cadence.catalog.domain.Artist;
import com.cadence.catalog.domain.Genre;
import com.cadence.catalog.domain.Track;
import com.cadence.catalog.domain.TrackArtist;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface CatalogMapper {

    ArtistView toView(Artist artist);

    @Mapping(target = "id", source = "album.id")
    @Mapping(target = "createdAt", source = "album.createdAt")
    @Mapping(target = "updatedAt", source = "album.updatedAt")
    @Mapping(target = "genres", source = "album.genreNames")
    @Mapping(target = "artist", source = "artist")
    AlbumView toView(Album album, ArtistRef artist);

    AdminTrackView toAdminView(Track track);

    CreditView toView(TrackArtist credit);

    GenreView toView(Genre genre);
}
