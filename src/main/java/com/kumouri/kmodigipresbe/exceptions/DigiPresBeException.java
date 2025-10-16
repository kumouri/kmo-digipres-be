package com.kumouri.kmodigipresbe.exceptions;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
public class DigiPresBeException extends RuntimeException implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final int errorCode;
    private final int httpStatusCode;

    public DigiPresBeException(String message, int errorCode, int httpStatusCode) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatusCode = httpStatusCode;
    }

    public DigiPresBeException(Throwable cause, int errorCode, int httpStatusCode) {
        super(cause);
        this.errorCode = errorCode;
        this.httpStatusCode = httpStatusCode;
    }
}
