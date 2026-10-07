package com.thinklab.domain.exception;

/** Domain Exception: a REQUESTER tried to use the backup registry (staff only, ADR-032). RFC 7807 mapping: HTTP 403 Forbidden. */
public class BackupAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-BKP-00403";

    public BackupAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
