package com.cadence.common.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends CadenceException {

    public ConflictException(String code, String detail) {
        super(HttpStatus.CONFLICT, code, detail);
    }
}
