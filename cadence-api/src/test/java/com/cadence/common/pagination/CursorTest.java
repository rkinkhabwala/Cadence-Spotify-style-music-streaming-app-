package com.cadence.common.pagination;

import com.cadence.common.error.BadRequestException;
import com.cadence.events.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {

    @Test
    void roundTripsTypedValues() {
        Instant at = Instant.parse("2026-10-05T15:42:00.123Z");
        UUID id = UuidV7.generate();

        Cursor decoded = Cursor.decode(Cursor.of(at, id, 7L, "a|b \"quoted\"").encode());

        assertThat(decoded.instant(0)).isEqualTo(at);
        assertThat(decoded.uuid(1)).isEqualTo(id);
        assertThat(decoded.longValue(2)).isEqualTo(7L);
        assertThat(decoded.string(3)).isEqualTo("a|b \"quoted\"");
    }

    @Test
    void encodedCursorIsUrlSafe() {
        String encoded = Cursor.of("???>>>~~~", UuidV7.generate()).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void rejectsGarbageAndWrongTypes() {
        assertThatThrownBy(() -> Cursor.decode("not base64!")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Cursor.decode("e30")).isInstanceOf(BadRequestException.class); // "{}"
        Cursor cursor = Cursor.of("not-a-uuid");
        assertThatThrownBy(() -> cursor.uuid(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> cursor.instant(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> cursor.string(5)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void requestClampsLimit() {
        assertThat(CursorRequest.of(null, null).limit()).isEqualTo(20);
        assertThat(CursorRequest.of(0, null).limit()).isEqualTo(1);
        assertThat(CursorRequest.of(5000, null).limit()).isEqualTo(100);
        assertThat(CursorRequest.of(10, " ").isFirstPage()).isTrue();
        assertThat(CursorRequest.of(10, null).fetchSize()).isEqualTo(11);
    }

    @Test
    void pageHasNextCursorOnlyWhenMoreRowsExist() {
        CursorRequest request = CursorRequest.firstPage(3);
        List<Integer> fourRows = IntStream.rangeClosed(1, 4).boxed().toList();

        CursorPage<Integer> page = CursorPage.of(fourRows, request, Cursor::of);
        CursorPage<Integer> last = CursorPage.of(fourRows.subList(0, 3), request, Cursor::of);

        assertThat(page.items()).containsExactly(1, 2, 3);
        assertThat(Cursor.decode(page.nextCursor()).longValue(0)).isEqualTo(3);
        assertThat(last.nextCursor()).isNull();
        assertThat(page.map(i -> "#" + i).items()).containsExactly("#1", "#2", "#3");
    }
}
