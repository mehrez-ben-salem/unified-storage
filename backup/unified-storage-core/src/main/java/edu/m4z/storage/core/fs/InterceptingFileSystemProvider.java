package edu.m4z.storage.core.fs;

import edu.m4z.storage.core.MountPoint;
import edu.m4z.storage.core.MountRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

/**
 * Wraps the default (OS) {@link FileSystemProvider} and intercepts operations
 * on paths that match a registered mount.
 *
 * <h2>How it works</h2>
 * <pre>
 * Files.readAllBytes(Path.of("/data/reports/Q4.pdf"))
 *   → JDK calls the FileSystemProvider for the default FileSystem
 *   → InterceptingFileSystemProvider.newInputStream("/data/reports/Q4.pdf")
 *   → MountRegistry resolves: /data/reports → s3://reports-bucket (prefix=reports/)
 *   → S3StorageBackend.newInputStream("reports-bucket", "reports/Q4.pdf")
 *   → ChunkedInputStream for large files
 *   → byte[] returned to developer
 *
 * If the path does NOT match any mount → delegates to the original OS provider.
 * </pre>
 * ারে
 * <h2>Installation</h2>
 * At Spring Boot startup, the auto-configuration:
 * <ol>
 *   <li>Saves a reference to the real default FileSystemProvider</li>
 *   <li>Installs this intercepting provider as the default</li>
 *   <li>All subsequent Path.of() / Files.* calls go through here</li>
 * </ol>
 *
 * <p><b>The developer code is 100% unchanged.</b> No import, no bean, no annotation.
 * Path.of, Paths.get, Files.copy — all work as before.</p>
 */
public class InterceptingFileSystemProvider extends FileSystemProvider {

    private static final Logger log = LoggerFactory.getLogger(InterceptingFileSystemProvider.class);

    private final FileSystemProvider delegate;  // the real OS provider
    private final MountRegistry registry;

    public InterceptingFileSystemProvider(FileSystemProvider delegate, MountRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.registry = Objects.requireNonNull(registry);
    }

    public FileSystemProvider delegate() {
        return delegate;
    }

    public MountRegistry registry() {
        return registry;
    }

    @Override
    public String getScheme() {
        return delegate.getScheme();
    }

    // ── Path resolution ───────────────────────────────────────

    private record Resolved(MountPoint mount, StorageBackend backend, String bucket, String key) {
    }

    /**
     * If path matches a mount, resolve to backend + bucket + key.
     * Otherwise return null → delegate to OS.
     */
    private Resolved tryResolve(Path path) {
        if (!registry.hasMounts()) return null;
        String abs = path.toAbsolutePath().normalize().toString();
        MountPoint mount = registry.resolve(abs);
        if (mount == null) return null;
        StorageBackend backend = registry.backendFor(mount.scheme());
        if (backend == null) return null;
        String key = registry.toKey(mount, abs);
        return new Resolved(mount, backend, mount.bucket(), key);
    }

    // ── Intercepted operations ────────────────────────────────

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) return r.backend.newInputStream(r.bucket, r.key);
        return delegate.newInputStream(path, options);
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) return r.backend.newOutputStream(r.bucket, r.key);
        return delegate.newOutputStream(path, options);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) {
            boolean write = options.contains(StandardOpenOption.WRITE) ||
                    options.contains(StandardOpenOption.CREATE) ||
                    options.contains(StandardOpenOption.CREATE_NEW);
            if (write) return new StreamByteChannel.Output(r.backend.newOutputStream(r.bucket, r.key));
            else return new StreamByteChannel.Input(r.backend.newInputStream(r.bucket, r.key));
        }
        return delegate.newByteChannel(path, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        Resolved r = tryResolve(dir);
        if (r != null) {
            List<String> entries = r.backend.list(r.bucket, r.key);
            return new DirectoryStream<>() {
                @Override
                public Iterator<Path> iterator() {
                    return entries.stream()
                            .map(name -> (Path) dir.resolve(name))
                            .filter(p -> {
                                try {
                                    return filter == null || filter.accept(p);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            })
                            .iterator();
                }

                @Override
                public void close() {
                }
            };
        }
        return delegate.newDirectoryStream(dir, filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        Resolved r = tryResolve(dir);
        if (r != null) {
            r.backend.createDirectory(r.bucket, r.key);
            return;
        }
        delegate.createDirectory(dir, attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) {
            r.backend.delete(r.bucket, r.key);
            return;
        }
        delegate.delete(path);
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        Resolved rs = tryResolve(source);
        Resolved rt = tryResolve(target);

        if (rs != null && rt != null && rs.mount.scheme().equals(rt.mount.scheme())) {
            // Same backend → server-side copy
            rs.backend.copy(rs.bucket, rs.key, rt.bucket, rt.key);
        } else if (rs != null && rt != null) {
            // Cross-backend → stream through
            try (InputStream in = rs.backend.newInputStream(rs.bucket, rs.key);
                 OutputStream out = rt.backend.newOutputStream(rt.bucket, rt.key)) {
                in.transferTo(out);
            }
        } else if (rs != null) {
            // Mounted → local
            try (InputStream in = rs.backend.newInputStream(rs.bucket, rs.key);
                 OutputStream out = delegate.newOutputStream(target)) {
                in.transferTo(out);
            }
        } else if (rt != null) {
            // Local → mounted
            try (InputStream in = delegate.newInputStream(source);
                 OutputStream out = rt.backend.newOutputStream(rt.bucket, rt.key)) {
                in.transferTo(out);
            }
        } else {
            delegate.copy(source, target, options);
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        copy(source, target, options);
        delete(source);
    }

    @Override
    public boolean isSameFile(Path a, Path b) throws IOException {
        return delegate.isSameFile(a, b);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) return false;
        return delegate.isHidden(path);
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return delegate.getFileStore(path);
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) {
            if (!r.backend.exists(r.bucket, r.key))
                throw new NoSuchFileException(path.toString());
            return;
        }
        delegate.checkAccess(path, modes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... opts) {
        Resolved r = tryResolve(path);
        if (r != null && type == BasicFileAttributeView.class) {
            return (V) new BasicFileAttributeView() {
                @Override
                public String name() {
                    return "basic";
                }

                @Override
                public BasicFileAttributes readAttributes() throws IOException {
                    return r.backend.readAttributes(r.bucket, r.key);
                }

                @Override
                public void setTimes(FileTime l, FileTime a, FileTime c) {
                }
            };
        }
        return delegate.getFileAttributeView(path, type, opts);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... opts)
            throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) return (A) r.backend.readAttributes(r.bucket, r.key);
        return delegate.readAttributes(path, type, opts);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... opts) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) {
            BasicFileAttributes a = r.backend.readAttributes(r.bucket, r.key);
            return Map.of("size", a.size(), "lastModifiedTime", a.lastModifiedTime(),
                    "isDirectory", a.isDirectory(), "isRegularFile", a.isRegularFile());
        }
        return delegate.readAttributes(path, attributes, opts);
    }

    @Override
    public void setAttribute(Path path, String attr, Object val, LinkOption... opts) throws IOException {
        Resolved r = tryResolve(path);
        if (r != null) return;  // ignore on remote
        delegate.setAttribute(path, attr, val, opts);
    }

    // ── FileSystem delegation ─────────────────────────────────

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return delegate.newFileSystem(uri, env);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        return delegate.getFileSystem(uri);
    }

    @Override
    public Path getPath(URI uri) {
        return delegate.getPath(uri);
    }

    // ── Inner: ByteChannel adapters ───────────────────────────

    static class StreamByteChannel {
        static class Input implements SeekableByteChannel {
            private final InputStream in;
            private long pos;
            private boolean open = true;

            Input(InputStream in) {
                this.in = in;
            }

            @Override
            public int read(java.nio.ByteBuffer dst) throws IOException {
                byte[] b = new byte[dst.remaining()];
                int n = in.read(b);
                if (n > 0) {
                    dst.put(b, 0, n);
                    pos += n;
                }
                return n;
            }

            @Override
            public int write(java.nio.ByteBuffer s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long position() {
                return pos;
            }

            @Override
            public SeekableByteChannel position(long p) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long size() {
                return -1;
            }

            @Override
            public SeekableByteChannel truncate(long s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() throws IOException {
                open = false;
                in.close();
            }
        }

        static class Output implements SeekableByteChannel {
            private final OutputStream out;
            private long pos;
            private boolean open = true;

            Output(OutputStream out) {
                this.out = out;
            }

            @Override
            public int read(java.nio.ByteBuffer d) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int write(java.nio.ByteBuffer src) throws IOException {
                int n = src.remaining();
                byte[] b = new byte[n];
                src.get(b);
                out.write(b);
                pos += n;
                return n;
            }

            @Override
            public long position() {
                return pos;
            }

            @Override
            public SeekableByteChannel position(long p) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long size() {
                return pos;
            }

            @Override
            public SeekableByteChannel truncate(long s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() throws IOException {
                open = false;
                out.close();
            }
        }
    }
}
