package com.itaccess.portal.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException accessRequest(long id) {
        return new NotFoundException("Access request " + id + " was not found");
    }
}
