package com.mta.faultinjection.web;

import org.springframework.http.HttpStatus;

/** Checked as runtime from {@link FaultInjectorUiController} handlers. */
public final class FaultInjectorUiRequestException extends RuntimeException {

    private final HttpStatus status;

    public FaultInjectorUiRequestException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
