/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Bounded local exports/config snapshots, private on POSIX, replaced without following links. */
public final class PrivateFiles {
    private PrivateFiles() { }
    public static void rejectSymlinks(Path path) throws IOException {
        for(Path cursor=path.toAbsolutePath().normalize();cursor!=null;cursor=cursor.getParent())
            if(Files.isSymbolicLink(cursor))throw new IOException("Protected path contains a symbolic link");
    }
    public static byte[] read(Path path,int limit) throws IOException {
        if(limit<1)throw new IllegalArgumentException("Invalid byte limit");
        rejectSymlinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Protected file is not regular");
        try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(Math.addExact(limit,1));
            if(bytes.length>limit)throw new IOException("Protected file exceeds its size limit");
            return bytes;
        }
    }
    public static Path replace(Path supplied,String name,byte[] bytes,int maxBytes,int maxFiles) throws IOException {
        if(name==null||!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")||name.contains(".."))throw new IOException("Invalid protected filename");
        if(maxBytes<1||maxFiles<1||bytes.length>maxBytes)throw new IOException("Protected file exceeds its size limit");
        Path directory=supplied.toAbsolutePath().normalize();rejectSymlinks(directory);Files.createDirectories(directory);rejectSymlinks(directory);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid protected directory");
        permissions(directory,true);
        Path target=directory.resolve(name);rejectSymlinks(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid protected target");
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))try(var entries=Files.list(directory)) {
            if(entries.limit(maxFiles).count()>=maxFiles)throw new IOException("Protected directory is at capacity");
        }
        Path temporary=Files.createTempFile(directory,"guardian-",".tmp");
        try {
            permissions(temporary,false);
            Files.write(temporary,bytes,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS);
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
            rejectSymlinks(directory);rejectSymlinks(target);
            try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            return target;
        } finally {Files.deleteIfExists(temporary);}
    }
    private static void permissions(Path path,boolean directory) throws IOException {
        try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}
        catch(UnsupportedOperationException ignored){/* Non-POSIX systems require administrator-managed ACLs. */}
    }
}
