package edu.m4z.unifiedstorage.s3;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Set;

/**
 * S3 FileSystem bound to a bucket.
 * One bucket = one FileSystem.
 */
public class S3FileSystem extends FileSystem {

    private final S3FileSystemProvider provider;
    private final String bucket;
    private volatile boolean open = true;

    S3FileSystem(S3FileSystemProvider provider, String bucket) {
        this.provider = provider;
        this.bucket = bucket;
    }

    public String getBucket() {
        return bucket;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        open = false;
        provider.removeFileSystem(bucket);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singletonList(new S3Path(this, "/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.emptyList();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "posix");
    }

    @Override
    public Path getPath(String first, String... more) {
        StringBuilder sb = new StringBuilder(first);
        for (String m : more) {
            if (!sb.toString().endsWith("/") && !m.startsWith("/")) sb.append("/");
            sb.append(m);
        }
        return new S3Path(this, sb.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("PathMatcher not supported for S3");
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("UserPrincipalLookupService not supported for S3");
    }

    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException("WatchService not supported for S3");
    }
}
