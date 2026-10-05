package com.cadence.common.error;

import org.springframework.http.HttpStatus;

/** Optimistic-locking mismatch on {@code If-Match} (412). */
public class PreconditionFailedException extends CadenceException {

    public PreconditionFailedException(String detail) {
        super(HttpStatus.PRECONDITION_FAILED, "version-mismatch", detail);
    }
}
