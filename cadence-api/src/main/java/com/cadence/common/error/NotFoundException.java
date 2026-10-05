package com.cadence.common.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends CadenceException {

    public NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "not-found", resource + " " + id + " was not found");
    }
}
