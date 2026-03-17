package edu.m4z.storage.core.fs;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

public class StorageFileAttributes implements BasicFileAttributes {
    private final long size;
    private final FileTime lastMod;
    private final boolean dir;

    public StorageFileAttributes(long size, Instant lastMod, boolean dir) {
        this.size = size;
        this.lastMod = lastMod != null ? FileTime.from(lastMod) : FileTime.from(Instant.EPOCH);
        this.dir = dir;
    }

    public static StorageFileAttributes directory() {
        return new StorageFileAttributes(0, null, true);
    }

    @Override
    public FileTime lastModifiedTime() {
        return lastMod;
    }

    @Override
    public FileTime lastAccessTime() {
        return lastMod;
    }

    @Override
    public FileTime creationTime() {
        return lastMod;
    }

    @Override
    public boolean isRegularFile() {
        return !dir;
    }

    @Override
    public boolean isDirectory() {
        return dir;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public Object fileKey() {
        return null;
    }
}
