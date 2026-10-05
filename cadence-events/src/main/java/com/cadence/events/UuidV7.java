package com.cadence.events;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RFC 9562 UUID version 7 generator: 48-bit Unix-millis timestamp, 12-bit counter (monotonic within a
 * millisecond), 62 random bits. Ids from one JVM are strictly increasing, which keeps B-tree inserts cheap.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_COUNTER = 0xFFF;
    private static final UuidV7 DEFAULT = new UuidV7();

    private final ReentrantLock lock = new ReentrantLock();
    private long lastMillis = -1;
    private int counter;

    UuidV7() {
    }

    public static UUID generate() {
        return DEFAULT.next(System.currentTimeMillis());
    }

    UUID next(long nowMillis) {
        long millis;
        int seq;
        lock.lock();
        try {
            millis = Math.max(nowMillis, lastMillis); // never go backwards if the clock does
            if (millis == lastMillis) {
                if (counter == MAX_COUNTER) {
                    millis++;
                    counter = RANDOM.nextInt(MAX_COUNTER / 2);
                } else {
                    counter++;
                }
            } else {
                counter = RANDOM.nextInt(MAX_COUNTER / 2); // leave headroom for increments
            }
            lastMillis = millis;
            seq = counter;
        } finally {
            lock.unlock();
        }
        long msb = (millis << 16) | 0x7000L | seq;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    /** The creation instant encoded in a v7 UUID. */
    public static Instant timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }
}
