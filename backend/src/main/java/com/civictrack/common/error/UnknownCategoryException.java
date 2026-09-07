package com.civictrack.common.error;

public class UnknownCategoryException extends RuntimeException {

    private final String code;

    public UnknownCategoryException(String code) {
        super("Unknown or inactive category: " + code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
