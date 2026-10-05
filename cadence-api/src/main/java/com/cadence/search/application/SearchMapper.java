package com.cadence.search.application;

import com.cadence.search.application.SearchViews.AlbumHit;
import com.cadence.search.application.SearchViews.ArtistHit;
import com.cadence.search.application.SearchViews.OwnerRef;
import com.cadence.search.application.SearchViews.PlaylistHit;
import com.cadence.search.domain.SearchDocuments.AlbumDoc;
import com.cadence.search.domain.SearchDocuments.ArtistDoc;
import com.cadence.search.domain.SearchDocuments.PlaylistDoc;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface SearchMapper {

    ArtistHit toHit(ArtistDoc doc);

    @Mapping(target = "artist.id", source = "artistId")
    @Mapping(target = "artist.name", source = "artistName")
    AlbumHit toHit(AlbumDoc doc);

    @Mapping(target = "owner", source = "owner")
    @Mapping(target = "id", source = "doc.id")
    @Mapping(target = "name", source = "doc.name")
    @Mapping(target = "description", source = "doc.description")
    @Mapping(target = "coverUrl", source = "doc.coverUrl")
    @Mapping(target = "trackCount", source = "doc.trackCount")
    PlaylistHit toHit(PlaylistDoc doc, OwnerRef owner);
}
