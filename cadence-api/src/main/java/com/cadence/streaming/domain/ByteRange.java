package com.cadence.streaming.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single satisfiable byte range of a resource of {@code size} bytes ({@code first..last} inclusive).
 * Supports {@code bytes=a-b}, {@code bytes=a-} and suffix ranges {@code bytes=-n}.
 */
public record ByteRange(long first, long last, long size) {

    private static final Pattern SINGLE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /** Thrown for malformed, multi-range or unsatisfiable headers (→ 416). */
    public static class InvalidRangeException extends RuntimeException {
        public InvalidRangeException(String message) {
            super(message);
        }
    }

    public long length() {
        return last - first + 1;
    }

    public boolean isPartial() {
        return first != 0 || last != size - 1;
    }

    public String contentRange() {
        return "bytes " + first + "-" + last + "/" + size;
    }

    /** No header → the whole resource. */
    public static ByteRange parse(String header, long size) {
        Optional<String> value = Optional.ofNullable(header).map(String::strip).filter(h -> !h.isEmpty());
        if (value.isEmpty()) {
            return new ByteRange(0, size - 1, size);
        }
        Matcher matcher = SINGLE.matcher(value.get());
        if (!matcher.matches() || matcher.group(1).isEmpty() && matcher.group(2).isEmpty()) {
            throw new InvalidRangeException("Only a single 'bytes=first-last' range is supported");
        }
        try {
            long first;
            long last;
            if (matcher.group(1).isEmpty()) { // suffix: last n bytes
                long n = Long.parseLong(matcher.group(2));
                if (n == 0) {
                    throw new InvalidRangeException("Empty suffix range");
                }
                first = Math.max(0, size - n);
                last = size - 1;
            } else {
                first = Long.parseLong(matcher.group(1));
                last = matcher.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(matcher.group(2)), size - 1);
            }
            if (first >= size || first > last) {
                throw new InvalidRangeException("Range not satisfiable for a resource of " + size + " bytes");
            }
            return new ByteRange(first, last, size);
        } catch (NumberFormatException e) {
            throw new InvalidRangeException("Range values are too large");
        }
    }
}
