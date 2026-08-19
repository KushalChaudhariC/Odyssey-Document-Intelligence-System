package com.calfuslearning.docusense_backend.exception;

import org.springframework.http.HttpStatus;

/** Thrown when the client sent an invalid or incomplete request (bad/missing body, empty file, etc). */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
