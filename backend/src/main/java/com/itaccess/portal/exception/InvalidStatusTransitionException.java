package com.itaccess.portal.exception;

import com.itaccess.portal.domain.RequestStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(long id, RequestStatus current, RequestStatus requested) {
        super("Access request " + id + " is " + current + " and can no longer be changed to " + requested
                + "; only PENDING requests can be reviewed");
    }
}
