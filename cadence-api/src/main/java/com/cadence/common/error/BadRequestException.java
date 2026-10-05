package com.cadence.common.error;

import org.springframework.http.HttpStatus;

public class BadRequestException extends CadenceException {

    public BadRequestException(String code, String detail) {
        super(HttpStatus.BAD_REQUEST, code, detail);
    }
}
