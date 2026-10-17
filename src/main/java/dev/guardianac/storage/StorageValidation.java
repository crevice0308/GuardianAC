// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

final class StorageValidation {
    private StorageValidation() { }
    static void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("Invalid " + field);
    }
    static void caseId(String value) {
        if (value == null || !value.matches("GAC-[A-Z0-9]{8,32}"))
            throw new IllegalArgumentException("Invalid case identifier");
    }
}
