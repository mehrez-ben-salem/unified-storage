package edu.m4z.unifiedstorage.s3;

import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.util.Iterator;

/**
 * Implementation of {@link Path} for S3 paths.
 *
 * Canonical URI : s3://bucket-name/key/to/object
 * - Scheme  : "s3"
 * - Host    : nom du bucket
 * - Path    : clé de l'objet (commence par '/')
 */
public class S3Path implements Path {

    private final S3FileSystem fileSystem;
    private final String key;  // path without the bucket, e.g. "/data/reports/file.csv"

    S3Path(S3FileSystem fileSystem, String key) {
        this.fileSystem = fileSystem;
        this.key = key.startsWith("/") ? key : "/" + key;
    }

    public String getBucket() {
        return fileSystem.getBucket();
    }

    /** S3 key without leading slash. Ex: "data/reports/file.csv" */
    public String getS3Key() {
        return key.startsWith("/") ? key.substring(1) : key;
    }

    public String getKey() {
        return key;
    }

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public URI toUri() {
        return URI.create("s3://" + getBucket() + key);
    }

    @Override
    public Path getRoot() {
        return isAbsolute() ? new S3Path(fileSystem, "/") : null;
    }

    @Override
    public Path toRealPath(java.nio.file.LinkOption... options) throws java.io.IOException {
        return toAbsolutePath();
    }

    @Override
    public Path getFileName() {
        int lastSlash = key.lastIndexOf('/');
        String name = lastSlash >= 0 ? key.substring(lastSlash + 1) : key;
        return new S3Path(fileSystem, name);
    }

    @Override
    public Path getParent() {
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash <= 0) return null;
        return new S3Path(fileSystem, key.substring(0, lastSlash));
    }

    @Override
    public Path resolve(String other) {
        if (other.isEmpty()) return this;
        if (other.startsWith("/")) return new S3Path(fileSystem, other);
        String base = key.endsWith("/") ? key : key + "/";
        return new S3Path(fileSystem, base + other);
    }

    @Override
    public Path resolve(Path other) {
        return resolve(other.toString());
    }

    @Override
    public boolean isAbsolute() {
        return key.startsWith("/");
    }

    @Override
    public Path toAbsolutePath() {
        return this;
    }

    @Override
    public int getNameCount() {
        String[] parts = key.split("/");
        int count = 0;
        for (String p : parts) if (!p.isEmpty()) count++;
        return count;
    }

    @Override
    public Path getName(int index) {
        String[] parts = key.split("/");
        int i = 0;
        for (String p : parts) {
            if (!p.isEmpty()) {
                if (i == index) return new S3Path(fileSystem, p);
                i++;
            }
        }
        throw new IllegalArgumentException("Index out of bounds: " + index);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        String[] parts = key.split("/");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String p : parts) {
            if (!p.isEmpty()) {
                if (i >= beginIndex && i < endIndex) {
                    if (!sb.isEmpty()) sb.append("/");
                    sb.append(p);
                }
                i++;
            }
        }
        return new S3Path(fileSystem, sb.toString());
    }

    @Override
    public boolean startsWith(Path other) {
        return key.startsWith(other.toString());
    }

    @Override
    public boolean endsWith(Path other) {
        return key.endsWith(other.toString());
    }

    @Override
    public Path normalize() {
        return this;
    }

    @Override
    public Path relativize(Path other) {
        String otherKey = ((S3Path) other).key;
        if (otherKey.startsWith(key)) {
            String rel = otherKey.substring(key.length());
            return new S3Path(fileSystem, rel.startsWith("/") ? rel.substring(1) : rel);
        }
        throw new IllegalArgumentException("Cannot relativize " + other + " against " + this);
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                             WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("WatchService not supported for S3");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) {
        throw new UnsupportedOperationException("WatchService not supported for S3");
    }

        @Override
    public File toFile() {
        throw new UnsupportedOperationException("S3Path cannot be converted to File");
    }

    @Override
    public Iterator<Path> iterator() {
        return new Iterator<>() {
            private int index = 0;
            public boolean hasNext() { return index < getNameCount(); }
            public Path next() { return getName(index++); }
        };
    }

    @Override
    public int compareTo(Path other) {
        return key.compareTo(((S3Path) other).key);
    }

    @Override
    public String toString() {
        return "s3://" + getBucket() + key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof S3Path other)) return false;
        return getBucket().equals(other.getBucket()) && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return 31 * getBucket().hashCode() + key.hashCode();
    }
}
