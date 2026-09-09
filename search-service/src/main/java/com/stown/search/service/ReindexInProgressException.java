package com.stown.search.service;

public class ReindexInProgressException extends RuntimeException {

    public ReindexInProgressException() {
        super("A reindex is already running");
    }
}
