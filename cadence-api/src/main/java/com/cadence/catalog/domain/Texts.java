package com.cadence.catalog.domain;

final class Texts {

    private Texts() {
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
