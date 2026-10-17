// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

final class SecurePaths {
    private SecurePaths() { }
    static Path directory(Path supplied) throws IOException {
        Path path = supplied.toAbsolutePath().normalize();
        rejectSymlinks(path);
        Files.createDirectories(path);
        rejectSymlinks(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Storage directory is invalid");
        privatePermissions(path, true);
        return path;
    }
    static void rejectSymlinks(Path path) throws IOException {
        Path cursor = path.toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symbolic links are not allowed for protected storage");
            cursor = cursor.getParent();
        }
    }
    static void privatePermissions(Path path, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException ignored) { /* Windows relies on the administrator's data-directory ACL. */ }
    }
}
