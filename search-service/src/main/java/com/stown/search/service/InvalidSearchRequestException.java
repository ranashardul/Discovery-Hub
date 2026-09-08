package com.stown.search.service;

import lombok.Getter;

@Getter
public class InvalidSearchRequestException extends RuntimeException {

    private final String field;

    public InvalidSearchRequestException(String field, String message) {
        super(message);
        this.field = field;
    }
}
