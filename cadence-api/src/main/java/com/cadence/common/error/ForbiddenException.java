package com.cadence.common.error;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends CadenceException {

    public ForbiddenException(String detail) {
        super(HttpStatus.FORBIDDEN, "forbidden", detail);
    }

    public ForbiddenException(String code, String detail) {
        super(HttpStatus.FORBIDDEN, code, detail);
    }
}
