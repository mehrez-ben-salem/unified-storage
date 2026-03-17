package edu.m4z.unifiedstorage.gcs;

import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.util.Iterator;

/**
 * Implementation of {@link Path} for GCS paths.
 *
 * Canonical URI : gs://bucket-name/blob/name
 * - Scheme : "gs"
 * - Host   : nom du bucket
 * - Path   : nom du blob (commence par '/')
 */
public class GcsPath implements Path {

    private final GcsFileSystem fileSystem;
    private final String blobPath; // e.g. "/data/reports/file.csv"

    GcsPath(GcsFileSystem fileSystem, String blobPath) {
        this.fileSystem = fileSystem;
        this.blobPath = blobPath.startsWith("/") ? blobPath : "/" + blobPath;
    }

    public String getBucket() {
        return fileSystem.getBucket();
    }

    /** Blob name without leading slash. Ex: "data/reports/file.csv" */
    public String getBlobName() {
        return blobPath.startsWith("/") ? blobPath.substring(1) : blobPath;
    }

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public URI toUri() {
        return URI.create("gs://" + getBucket() + blobPath);
    }

    @Override
    public Path getRoot() {
        return isAbsolute() ? new GcsPath(fileSystem, "/") : null;
    }

    @Override
    public Path toRealPath(java.nio.file.LinkOption... options) throws java.io.IOException {
        return toAbsolutePath();
    }

    @Override
    public Path getFileName() {
        int lastSlash = blobPath.lastIndexOf('/');
        String name = lastSlash >= 0 ? blobPath.substring(lastSlash + 1) : blobPath;
        return new GcsPath(fileSystem, name);
    }

    @Override
    public Path getParent() {
        int lastSlash = blobPath.lastIndexOf('/');
        if (lastSlash <= 0) return null;
        return new GcsPath(fileSystem, blobPath.substring(0, lastSlash));
    }

    @Override
    public Path resolve(String other) {
        if (other.isEmpty()) return this;
        if (other.startsWith("/")) return new GcsPath(fileSystem, other);
        String base = blobPath.endsWith("/") ? blobPath : blobPath + "/";
        return new GcsPath(fileSystem, base + other);
    }

    @Override
    public Path resolve(Path other) {
        return resolve(other.toString());
    }

    @Override
    public boolean isAbsolute() { return blobPath.startsWith("/"); }

    @Override
    public Path toAbsolutePath() { return this; }

    @Override
    public int getNameCount() {
        String[] parts = blobPath.split("/");
        int count = 0;
        for (String p : parts) if (!p.isEmpty()) count++;
        return count;
    }

    @Override
    public Path getName(int index) {
        String[] parts = blobPath.split("/");
        int i = 0;
        for (String p : parts) {
            if (!p.isEmpty()) {
                if (i == index) return new GcsPath(fileSystem, p);
                i++;
            }
        }
        throw new IllegalArgumentException("Index out of bounds: " + index);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        String[] parts = blobPath.split("/");
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
        return new GcsPath(fileSystem, sb.toString());
    }

    @Override
    public boolean startsWith(Path other) { return blobPath.startsWith(other.toString()); }

    @Override
    public boolean endsWith(Path other) { return blobPath.endsWith(other.toString()); }

    @Override
    public Path normalize() { return this; }

    @Override
    public Path relativize(Path other) {
        String otherPath = ((GcsPath) other).blobPath;
        if (otherPath.startsWith(blobPath)) {
            String rel = otherPath.substring(blobPath.length());
            return new GcsPath(fileSystem, rel.startsWith("/") ? rel.substring(1) : rel);
        }
        throw new IllegalArgumentException("Cannot relativize " + other + " against " + this);
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                             WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("WatchService not supported for GCS");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) {
        throw new UnsupportedOperationException("WatchService not supported for GCS");
    }

        @Override
    public File toFile() {
        throw new UnsupportedOperationException("GcsPath cannot be converted to File");
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
        return blobPath.compareTo(((GcsPath) other).blobPath);
    }

    @Override
    public String toString() { return "gs://" + getBucket() + blobPath; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GcsPath other)) return false;
        return getBucket().equals(other.getBucket()) && blobPath.equals(other.blobPath);
    }

    @Override
    public int hashCode() {
        return 31 * getBucket().hashCode() + blobPath.hashCode();
    }
}
