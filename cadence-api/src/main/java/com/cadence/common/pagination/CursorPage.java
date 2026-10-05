package com.cadence.common.pagination;

import java.util.List;
import java.util.function.Function;

/** Response shape for every collection endpoint: {@code { "items": [...], "nextCursor": "..." }}. */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }

    /**
     * Builds a page from rows fetched with {@link CursorRequest#fetchSize()}.
     *
     * @param cursorOf sort-key values of an item, used to build the next cursor
     */
    public static <T> CursorPage<T> of(List<T> fetched, CursorRequest request, Function<T, Cursor> cursorOf) {
        if (fetched.size() <= request.limit()) {
            return new CursorPage<>(fetched, null);
        }
        List<T> page = fetched.subList(0, request.limit());
        return new CursorPage<>(page, cursorOf.apply(page.getLast()).encode());
    }

    public <R> CursorPage<R> map(Function<? super T, ? extends R> mapper) {
        return new CursorPage<>(items.stream().<R>map(mapper).toList(), nextCursor);
    }
}
