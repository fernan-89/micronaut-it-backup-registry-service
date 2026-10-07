package com.thinklab.domain.exception;

/** Domain Exception: an illegal BackupPolicy lifecycle transition, or the policy changed while the write was applied. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidBackupPolicyStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-BKP-00409";

    public InvalidBackupPolicyStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
