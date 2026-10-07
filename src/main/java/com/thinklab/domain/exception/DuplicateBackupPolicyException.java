package com.thinklab.domain.exception;

/** Domain Exception: a backup policy name already used in the organisation. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateBackupPolicyException extends BusinessException {

    private static final String ERROR_CODE = "ERR-BKP-00409";

    public DuplicateBackupPolicyException(String message) {
        super(ERROR_CODE, message);
    }
}
