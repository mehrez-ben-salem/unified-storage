package edu.m4z.unifiedstorage.gcs;

import lombok.extern.slf4j.Slf4j;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;
import edu.m4z.unifiedstorage.config.StorageProperties.MountPointProperties;
import edu.m4z.unifiedstorage.core.SpringAwareFileSystemProviderBridge;
import edu.m4z.unifiedstorage.posix.PosixMetadataService;
import edu.m4z.unifiedstorage.spi.MultipartUploadHandle;
import edu.m4z.unifiedstorage.spi.UnifiedFileSystemProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GCS provider — Spring bean AND Java SPI FileSystemProvider.
 *
 * Same pattern as S3FileSystemProvider:
 * the ServiceLoader instance delegates to the real Spring bean via
 * {@link SpringAwareFileSystemProviderBridge}.
 *
 * POSIX operations are delegated to {@link PosixMetadataService}, which
 * respects the {@code unified-storage.posix.enabled} flag from application.yml.
 */
@Slf4j
@Component
public class GcsFileSystemProvider extends UnifiedFileSystemProvider {
    private static final String SCHEME = "gs";

    @Autowired(required = false)
    private PosixMetadataService posixService;

    private final Map<String, Storage>             clientsByMount      = new ConcurrentHashMap<>();
    private final Map<String, GcsFileSystem>       fileSystemsByBucket = new ConcurrentHashMap<>();
    private final Map<String, MountPointProperties> mountConfigs       = new ConcurrentHashMap<>();
    private long defaultChunkSize = 64 * 1024 * 1024L;

    /** Required by Java SPI. */
    public GcsFileSystemProvider() {}

    private GcsFileSystemProvider springBean() {
        if (posixService != null) return this;
        if (SpringAwareFileSystemProviderBridge.isReady()) {
            return SpringAwareFileSystemProviderBridge.getBean(GcsFileSystemProvider.class);
        }
        return this;
    }

    @Override
    public String getScheme() { return SCHEME; }

    @Override
    public void registerMount(String mountName, MountPointProperties props) {
        mountConfigs.put(mountName, props);
        Storage client = buildClient(toGcsProps(props));
        clientsByMount.put(mountName, client);
        URI uri = URI.create(props.getPath());
        fileSystemsByBucket.computeIfAbsent(uri.getHost(), b -> new GcsFileSystem(this, b));
        log.info("GCS mount '{}' → bucket '{}' project '{}'",
                mountName, uri.getHost(), toGcsProps(props).getProjectId());
    }

    public void setDefaultChunkSize(long chunkSize) { this.defaultChunkSize = chunkSize; }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        return springBean().fileSystemsByBucket
                .computeIfAbsent(uri.getHost(), b -> new GcsFileSystem(springBean(), b));
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        GcsFileSystem fs = springBean().fileSystemsByBucket.get(uri.getHost());
        if (fs == null) throw new FileSystemNotFoundException("GCS bucket not configured: " + uri.getHost());
        return fs;
    }

    @Override
    public Path getPath(URI uri) {
        GcsFileSystemProvider bean = springBean();
        GcsFileSystem fs = bean.fileSystemsByBucket
                .computeIfAbsent(uri.getHost(), b -> new GcsFileSystem(bean, b));
        return new GcsPath(fs, uri.getPath());
    }

    void removeFileSystem(String bucket) { fileSystemsByBucket.remove(bucket); }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(path);
        Blob blob = bean.resolveClient(gcs).get(BlobId.of(gcs.getBucket(), gcs.getBlobName()));
        if (blob == null) throw new NoSuchFileException(gcs.toString());
        return Channels.newInputStream(blob.reader());
    }

    @Override
    public InputStream newRangeInputStream(Path path, long offset, long length) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(path);
        Blob blob = bean.resolveClient(gcs).get(BlobId.of(gcs.getBucket(), gcs.getBlobName()));
        if (blob == null) throw new NoSuchFileException(gcs.toString());
        ReadChannel reader = blob.reader();
        reader.seek(offset);
        reader.limit(offset + length);
        return Channels.newInputStream(reader);
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(path);
        return new GcsResumableOutputStream(gcs, bean.resolveClient(gcs),
                bean.resolveChunkSize(), bean.posixService);
    }

    @Override
    public MultipartUploadHandle initiateMultipartUpload(Path path) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(path);
        return new GcsResumableUploadHandle(gcs, bean.resolveClient(gcs),
                bean.resolveChunkSize(), bean.posixService);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                               FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException("SeekableByteChannel not supported for GCS.");
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
                                                     DirectoryStream.Filter<? super Path> filter) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcsDir = bean.toGcsPath(dir);
        String prefix = gcsDir.getBlobName().isEmpty() ? ""
                : (gcsDir.getBlobName().endsWith("/") ? gcsDir.getBlobName() : gcsDir.getBlobName() + "/");

        var blobs = bean.resolveClient(gcsDir).list(gcsDir.getBucket(),
                Storage.BlobListOption.prefix(prefix),
                Storage.BlobListOption.currentDirectory());

        List<Path> entries = new ArrayList<>();
        GcsFileSystem fs = (GcsFileSystem) bean.getFileSystem(URI.create("gs://" + gcsDir.getBucket()));
        for (Blob blob : blobs.iterateAll()) {
            GcsPath p = new GcsPath(fs, "/" + blob.getName());
            try { if (filter.accept(p)) entries.add(p); } catch (IOException ignore) {}
        }
        return new DirectoryStream<>() {
            public Iterator<Path> iterator() { return entries.iterator(); }
            public void close() {}
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(dir);
        String name = gcs.getBlobName().endsWith("/") ? gcs.getBlobName() : gcs.getBlobName() + "/";
        BlobInfo bi = BlobInfo.newBuilder(gcs.getBucket(), name).build();
        bean.resolveClient(gcs).create(bi, new byte[0]);
        bean.posixService.saveDirectory(gcs.toUri().toString());
    }

    @Override
    public void delete(Path path) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(path);
        boolean deleted = bean.resolveClient(gcs).delete(BlobId.of(gcs.getBucket(), gcs.getBlobName()));
        if (!deleted) throw new NoSuchFileException(gcs.toString());
        bean.posixService.delete(gcs.toUri().toString());
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        if (source instanceof GcsPath src && target instanceof GcsPath tgt
                && src.getBucket().equals(tgt.getBucket())) {
            GcsFileSystemProvider bean = springBean();
            bean.resolveClient(src).copy(Storage.CopyRequest.newBuilder()
                    .setSource(BlobId.of(src.getBucket(), src.getBlobName()))
                    .setTarget(BlobId.of(tgt.getBucket(), tgt.getBlobName()))
                    .build()).getResult();
            return;
        }
        throw new UnsupportedOperationException("Cross-provider copy via StorageRegistry");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        copy(source, target, options);
        delete(source);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) { return path.toUri().equals(path2.toUri()); }

    @Override
    public boolean isHidden(Path path) { return false; }

    @Override
    public FileStore getFileStore(Path path) {
        throw new UnsupportedOperationException("FileStore not supported for GCS");
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(path);
        Blob blob = bean.resolveClient(gcs).get(BlobId.of(gcs.getBucket(), gcs.getBlobName()));
        if (blob == null) throw new NoSuchFileException(gcs.toString());
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                  LinkOption... options) { return null; }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                             LinkOption... options) throws IOException {
        GcsFileSystemProvider bean = springBean();
        GcsPath gcs = bean.toGcsPath(path);
        Blob blob = bean.resolveClient(gcs).get(BlobId.of(gcs.getBucket(), gcs.getBlobName()));
        if (blob == null) throw new NoSuchFileException(gcs.toString());
        String vp = gcs.toUri().toString();
        var posix = bean.posixService.find(vp);

        return (A) new BasicFileAttributes() {
            public FileTime lastModifiedTime() {
                return FileTime.fromMillis(blob.getUpdateTimeOffsetDateTime().toInstant().toEpochMilli());
            }
            public FileTime lastAccessTime() {
                return posix.map(m -> FileTime.from(m.getAccessedAt())).orElse(lastModifiedTime());
            }
            public FileTime creationTime() {
                return FileTime.fromMillis(blob.getCreateTimeOffsetDateTime().toInstant().toEpochMilli());
            }
            public boolean isRegularFile() { return !blob.getName().endsWith("/"); }
            public boolean isDirectory()   { return blob.getName().endsWith("/"); }
            public boolean isSymbolicLink(){ return false; }
            public boolean isOther()       { return false; }
            public long size()             { return blob.getSize(); }
            public Object fileKey()        { return vp; }
        };
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
        return Collections.emptyMap();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        GcsFileSystemProvider bean = springBean();
        String vp = bean.toGcsPath(path).toUri().toString();
        bean.posixService.setAttribute(vp, attribute, value);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private static GcsMountProperties toGcsProps(MountPointProperties props) {
        if (props instanceof GcsMountProperties gcs) return gcs;
        GcsMountProperties gcs = new GcsMountProperties();
        gcs.setPath(props.getPath());
        gcs.setMultipartThreshold(props.getMultipartThreshold());
        gcs.setPartSize(props.getPartSize());
        return gcs;
    }

    private Storage buildClient(GcsMountProperties props) {
        try {
            StorageOptions.Builder builder = StorageOptions.newBuilder();
            if (props.getProjectId() != null) builder.setProjectId(props.getProjectId());
            if (props.getCredentialsJson() != null) {
                String creds = props.getCredentialsJson().trim();
                InputStream is = creds.startsWith("{")
                        ? new ByteArrayInputStream(creds.getBytes())
                        : new FileInputStream(creds);
                try (is) {
                    builder.setCredentials(ServiceAccountCredentials.fromStream(is));
                }
            }
            return builder.build().getService();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create GCS client", e);
        }
    }

    private GcsPath toGcsPath(Path path) {
        if (path instanceof GcsPath gcs) return gcs;
        return (GcsPath) getPath(path.toUri());
    }

    private Storage resolveClient(GcsPath path) {
        return clientsByMount.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No GCS client configured."));
    }

    private long resolveChunkSize() {
        return mountConfigs.values().stream()
                .filter(m -> m.getPartSize() != null)
                .map(MountPointProperties::getPartSize)
                .findFirst().orElse(defaultChunkSize);
    }
}
