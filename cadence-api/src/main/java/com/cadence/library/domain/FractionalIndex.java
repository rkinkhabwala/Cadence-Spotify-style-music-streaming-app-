package com.cadence.library.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Order keys that always leave room between neighbours, so moving or inserting one item never renumbers the
 * others (spec 4: "fractional/lexorank ordering"). Port of the well-known {@code fractional-indexing} algorithm
 * (D. Greenspan / rocicorp), base 62: keys are an integer part whose first character encodes its length plus an
 * optional fraction. Appending only increments the integer part, so 10,000 appends need keys of at most 4 characters.
 * Keys compare by plain byte order (Java {@code compareTo}, Postgres {@code COLLATE "C"}).
 */
public final class FractionalIndex {

    static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char ZERO = DIGITS.charAt(0);
    private static final char LAST = DIGITS.charAt(DIGITS.length() - 1);
    private static final String INTEGER_ZERO = "a" + ZERO;
    private static final String SMALLEST_INTEGER = "A" + String.valueOf(ZERO).repeat(26);

    private FractionalIndex() {
    }

    /**
     * A key strictly between {@code a} and {@code b}; {@code null} means "before the first" / "after the last".
     *
     * @throws IllegalArgumentException if a key is malformed or {@code a >= b}
     */
    public static String between(String a, String b) {
        if (a != null) {
            validateKey(a);
        }
        if (b != null) {
            validateKey(b);
        }
        if (a != null && b != null && a.compareTo(b) >= 0) {
            throw new IllegalArgumentException(a + " >= " + b);
        }
        if (a == null) {
            if (b == null) {
                return INTEGER_ZERO;
            }
            String ib = integerPart(b);
            String fb = b.substring(ib.length());
            if (ib.equals(SMALLEST_INTEGER)) {
                return ib + midpoint("", fb);
            }
            if (ib.compareTo(b) < 0) {
                return ib;
            }
            String decremented = decrementInteger(ib);
            if (decremented == null) {
                throw new IllegalArgumentException("Cannot decrement any more");
            }
            return decremented;
        }
        if (b == null) {
            String ia = integerPart(a);
            String fa = a.substring(ia.length());
            String incremented = incrementInteger(ia);
            return incremented == null ? ia + midpoint(fa, null) : incremented;
        }
        String ia = integerPart(a);
        String fa = a.substring(ia.length());
        String ib = integerPart(b);
        String fb = b.substring(ib.length());
        if (ia.equals(ib)) {
            return ia + midpoint(fa, fb);
        }
        String incremented = incrementInteger(ia);
        if (incremented == null) {
            throw new IllegalArgumentException("Cannot increment any more");
        }
        return incremented.compareTo(b) < 0 ? incremented : ia + midpoint(fa, null);
    }

    /** {@code n} ascending keys between {@code a} and {@code b}, bisecting so lengths stay balanced. */
    public static List<String> between(String a, String b, int n) {
        List<String> keys = new ArrayList<>(n);
        if (n <= 0) {
            return keys;
        }
        if (b == null) { // appending: increments keep keys short
            String previous = a;
            for (int i = 0; i < n; i++) {
                previous = between(previous, null);
                keys.add(previous);
            }
            return keys;
        }
        if (a == null) { // prepending: work backwards from b
            String next = b;
            for (int i = 0; i < n; i++) {
                next = between(null, next);
                keys.addFirst(next);
            }
            return keys;
        }
        int mid = n / 2;
        String middle = between(a, b);
        keys.addAll(between(a, middle, mid));
        keys.add(middle);
        keys.addAll(between(middle, b, n - mid - 1));
        return keys;
    }

    static String midpoint(String a, String b) {
        if (b != null && a.compareTo(b) >= 0) {
            throw new IllegalArgumentException(a + " >= " + b);
        }
        if (!a.isEmpty() && a.charAt(a.length() - 1) == ZERO || b != null && !b.isEmpty() && b.charAt(b.length() - 1) == ZERO) {
            throw new IllegalArgumentException("Trailing zero");
        }
        if (b != null) {
            int n = 0;
            while (n < b.length() && (n < a.length() ? a.charAt(n) : ZERO) == b.charAt(n)) {
                n++;
            }
            if (n > 0) {
                return b.substring(0, n) + midpoint(n < a.length() ? a.substring(n) : "", b.substring(n));
            }
        }
        int digitA = a.isEmpty() ? 0 : DIGITS.indexOf(a.charAt(0));
        int digitB = b != null ? DIGITS.indexOf(b.charAt(0)) : DIGITS.length();
        if (digitB - digitA > 1) {
            return String.valueOf(DIGITS.charAt((int) Math.round(0.5 * (digitA + digitB))));
        }
        if (b != null && b.length() > 1) {
            return b.substring(0, 1);
        }
        return DIGITS.charAt(digitA) + midpoint(a.isEmpty() ? "" : a.substring(1), null);
    }

    static int integerLength(char head) {
        if (head >= 'a' && head <= 'z') {
            return head - 'a' + 2;
        }
        if (head >= 'A' && head <= 'Z') {
            return 'Z' - head + 2;
        }
        throw new IllegalArgumentException("Invalid order key head: " + head);
    }

    static String integerPart(String key) {
        int length = integerLength(key.charAt(0));
        if (length > key.length()) {
            throw new IllegalArgumentException("Invalid order key: " + key);
        }
        return key.substring(0, length);
    }

    /** @throws IllegalArgumentException for keys this algorithm cannot have produced */
    public static void validateKey(String key) {
        if (key == null || key.isEmpty() || key.equals(SMALLEST_INTEGER)) {
            throw new IllegalArgumentException("Invalid order key: " + key);
        }
        String integer = integerPart(key);
        if (key.length() > integer.length() && key.charAt(key.length() - 1) == ZERO) {
            throw new IllegalArgumentException("Invalid order key (trailing zero): " + key);
        }
        for (char c : key.substring(1).toCharArray()) {
            if (DIGITS.indexOf(c) < 0) {
                throw new IllegalArgumentException("Invalid order key character: " + key);
            }
        }
    }

    private static String incrementInteger(String integer) {
        char head = integer.charAt(0);
        char[] digits = integer.substring(1).toCharArray();
        boolean carry = true;
        for (int i = digits.length - 1; carry && i >= 0; i--) {
            int d = DIGITS.indexOf(digits[i]) + 1;
            if (d == DIGITS.length()) {
                digits[i] = ZERO;
            } else {
                digits[i] = DIGITS.charAt(d);
                carry = false;
            }
        }
        if (!carry) {
            return head + new String(digits);
        }
        if (head == 'Z') {
            return "a" + ZERO;
        }
        if (head == 'z') {
            return null;
        }
        char next = (char) (head + 1);
        String rest = new String(digits);
        rest = next > 'a' ? rest + ZERO : rest.substring(0, rest.length() - 1);
        return next + rest;
    }

    private static String decrementInteger(String integer) {
        char head = integer.charAt(0);
        char[] digits = integer.substring(1).toCharArray();
        boolean borrow = true;
        for (int i = digits.length - 1; borrow && i >= 0; i--) {
            int d = DIGITS.indexOf(digits[i]) - 1;
            if (d == -1) {
                digits[i] = LAST;
            } else {
                digits[i] = DIGITS.charAt(d);
                borrow = false;
            }
        }
        if (!borrow) {
            return head + new String(digits);
        }
        if (head == 'a') {
            return "Z" + LAST;
        }
        if (head == 'A') {
            return null;
        }
        char previous = (char) (head - 1);
        String rest = new String(digits);
        rest = previous < 'Z' ? rest + LAST : rest.substring(0, rest.length() - 1);
        return previous + rest;
    }
}
