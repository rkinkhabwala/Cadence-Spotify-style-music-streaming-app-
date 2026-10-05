package com.cadence.common.pagination;

/**
 * Parsed {@code ?limit=&cursor=} query parameters. {@code limit} defaults to 20 and is clamped to 1..100;
 * {@code cursor} is {@code null} for the first page. Repositories should fetch {@link #fetchSize()} rows.
 */
public record CursorRequest(int limit, Cursor cursor) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public static CursorRequest of(Integer limit, String cursor) {
        int effective = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        return new CursorRequest(effective, cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor));
    }

    public static CursorRequest firstPage(int limit) {
        return of(limit, null);
    }

    public boolean isFirstPage() {
        return cursor == null;
    }

    /** One extra row tells us whether a next page exists. */
    public int fetchSize() {
        return limit + 1;
    }
}
