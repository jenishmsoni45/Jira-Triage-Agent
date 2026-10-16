package com.jenishsoni.jiratriage.domain;

public class OperationException extends RuntimeException {

    private final String code;

    public OperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
