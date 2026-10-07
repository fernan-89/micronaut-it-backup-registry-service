package com.thinklab.domain.exception;

/** Domain Exception: no backup policy with that id in the tenant (another tenant's answers the same). RFC 7807 mapping: HTTP 404 Not Found. */
public class BackupPolicyNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-BKP-00404";

    public BackupPolicyNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
