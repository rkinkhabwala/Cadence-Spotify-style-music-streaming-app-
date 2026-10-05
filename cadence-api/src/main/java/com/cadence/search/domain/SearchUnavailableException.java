package com.cadence.search.domain;

import com.cadence.common.error.CadenceException;
import org.springframework.http.HttpStatus;

/** Elasticsearch could not be reached (503 {@code search-unavailable}). */
public class SearchUnavailableException extends CadenceException {

    public SearchUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "search-unavailable", "Search is temporarily unavailable; try again shortly");
        initCause(cause);
    }
}
