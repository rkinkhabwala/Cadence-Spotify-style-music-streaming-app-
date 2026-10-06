package com.cadence.library.domain;

import com.cadence.common.error.ConflictException;
import com.cadence.common.error.ForbiddenException;
import com.cadence.common.error.NotFoundException;
import com.cadence.library.domain.Playlist.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaylistTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void rolesFollowOwnershipCollaborationAndVisibility() {
        Playlist privateCollab = new Playlist(owner, "Mix", null, Visibility.PRIVATE, true, NOW);
        Playlist publicSolo = new Playlist(owner, "Mix", null, Visibility.PUBLIC, false, NOW);

        assertThat(privateCollab.roleOf(owner, false)).isEqualTo(Role.OWNER);
        assertThat(privateCollab.roleOf(other, true)).isEqualTo(Role.COLLABORATOR);
        assertThatThrownBy(() -> privateCollab.roleOf(other, false)).isInstanceOf(NotFoundException.class);
        assertThat(publicSolo.roleOf(other, false)).isEqualTo(Role.LISTENER);
        assertThat(publicSolo.roleOf(other, true)).as("joined, but no longer collaborative").isEqualTo(Role.LISTENER);
    }

    @Test
    void collaboratorsEditTracksButOnlyTheOwnerManagesThePlaylist() {
        Playlist playlist = new Playlist(owner, "Mix", null, Visibility.PUBLIC, true, NOW);

        playlist.requireEditor(owner, false);
        playlist.requireEditor(other, true);
        assertThatThrownBy(() -> playlist.requireEditor(other, false)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> playlist.requireOwner(other, true)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theInviteLinkIsStableUntilRevokedAndDiesWithCollaboration() {
        Playlist playlist = new Playlist(owner, "Mix", null, Visibility.PRIVATE, false, NOW);
        assertThatThrownBy(() -> playlist.invite(() -> "t1")).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("not-collaborative"));

        playlist.update(null, null, null, true, NOW);
        assertThat(playlist.invite(() -> "t1")).isEqualTo("t1");
        assertThat(playlist.invite(() -> "t2")).as("idempotent").isEqualTo("t1");
        assertThat(playlist.acceptsInvite("t1")).isTrue();
        assertThat(playlist.acceptsInvite("t2")).isFalse();
        assertThat(playlist.acceptsInvite(null)).isFalse();

        playlist.revokeInvite();
        assertThat(playlist.acceptsInvite("t1")).isFalse();
        assertThat(playlist.invite(() -> "t3")).isEqualTo("t3");

        playlist.update(null, null, null, false, NOW);
        assertThat(playlist.getInviteToken()).isNull();
        assertThat(playlist.acceptsInvite("t3")).isFalse();
    }
}
