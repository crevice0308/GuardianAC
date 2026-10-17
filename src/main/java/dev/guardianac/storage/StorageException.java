// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

/** Safe, deliberately non-sensitive storage failure. Never contains player IPs or keys. */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public StorageException(String message) { super(message); }
}
