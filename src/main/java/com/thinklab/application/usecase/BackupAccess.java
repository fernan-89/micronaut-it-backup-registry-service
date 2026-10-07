package com.thinklab.application.usecase;

import com.thinklab.domain.exception.BackupAccessDeniedException;

/** The backup registry is for IT staff and for the tools they run (ADR-032): a REQUESTER is refused everywhere. */
final class BackupAccess {

    static final String REQUESTER_ROLE = "REQUESTER";

    private BackupAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new BackupAccessDeniedException("A requester cannot " + operation + ": backups are handled by IT staff.");
        }
    }
}
