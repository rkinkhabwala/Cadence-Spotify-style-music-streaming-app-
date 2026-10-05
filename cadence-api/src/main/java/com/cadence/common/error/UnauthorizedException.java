package com.cadence.common.error;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends CadenceException {

    public UnauthorizedException(String code, String detail) {
        super(HttpStatus.UNAUTHORIZED, code, detail);
    }
}
