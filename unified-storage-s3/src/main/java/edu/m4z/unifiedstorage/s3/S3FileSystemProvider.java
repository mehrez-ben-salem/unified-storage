package edu.m4z.unifiedstorage.s3;

import lombok.extern.slf4j.Slf4j;

import edu.m4z.unifiedstorage.config.StorageProperties.MountPointProperties;
import edu.m4z.unifiedstorage.core.SpringAwareFileSystemProviderBridge;
import edu.m4z.unifiedstorage.posix.PosixMetadata;
import edu.m4z.unifiedstorage.posix.PosixMetadataService;
import edu.m4z.unifiedstorage.spi.MultipartUploadHandle;
import edu.m4z.unifiedstorage.spi.UnifiedFileSystemProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3 provider — Spring bean AND Java SPI FileSystemProvider.
 *
 * SPI + SPRING PATTERN:
 * Java SPI (ServiceLoader) instantiates this class via the no-arg constructor.
 * This "shadow" instance delegates to the real Spring bean via
 * {@link SpringAwareFileSystemProviderBridge}, which holds the Spring context.
 *
 * Result: Path.of(URI.create("s3://bucket/key")) works natively,
 * and the Spring bean benefits from full injection (PosixMetadataService, etc.).
 *
 * POSIX operations are delegated to {@link PosixMetadataService}, which
 * respects the {@code unified-storage.posix.enabled} flag from application.yml.
 */
@Slf4j
@Component
public class S3FileSystemProvider extends UnifiedFileSystemProvider {
    private static final String SCHEME = "s3";

    @Autowired(required = false)
    private PosixMetadataService posixService;

    private final Map<String, S3AsyncClient>       clientsByMount      = new ConcurrentHashMap<>();
    private final Map<String, S3FileSystem>        fileSystemsByBucket = new ConcurrentHashMap<>();
    private final Map<String, MountPointProperties> mountConfigs       = new ConcurrentHashMap<>();
    private long defaultChunkSize = 64 * 1024 * 1024L;

    /** Required by Java SPI. The ServiceLoader-created instance delegates to the Spring bean. */
    public S3FileSystemProvider() {}

    private S3FileSystemProvider springBean() {
        if (posixService != null) return this;
        if (SpringAwareFileSystemProviderBridge.isReady()) {
            return SpringAwareFileSystemProviderBridge.getBean(S3FileSystemProvider.class);
        }
        return this;
    }

    @Override
    public String getScheme() { return SCHEME; }

    @Override
    public void registerMount(String mountName, MountPointProperties props) {
        mountConfigs.put(mountName, props);
        S3AsyncClient client = buildClient(toS3Props(props));
        clientsByMount.put(mountName, client);
        URI uri = URI.create(props.getPath());
        fileSystemsByBucket.computeIfAbsent(uri.getHost(), b -> new S3FileSystem(this, b));
        log.info("S3 mount '{}' → bucket '{}' region '{}'",
                mountName, uri.getHost(), toS3Props(props).getRegion());
    }

    public void setDefaultChunkSize(long chunkSize) { this.defaultChunkSize = chunkSize; }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        return springBean().fileSystemsByBucket
                .computeIfAbsent(uri.getHost(), b -> new S3FileSystem(springBean(), b));
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        S3FileSystem fs = springBean().fileSystemsByBucket.get(uri.getHost());
        if (fs == null) throw new FileSystemNotFoundException("S3 bucket not configured: " + uri.getHost());
        return fs;
    }

    @Override
    public Path getPath(URI uri) {
        S3FileSystemProvider bean = springBean();
        S3FileSystem fs = bean.fileSystemsByBucket
                .computeIfAbsent(uri.getHost(), b -> new S3FileSystem(bean, b));
        return new S3Path(fs, uri.getPath());
    }

    void removeFileSystem(String bucket) { fileSystemsByBucket.remove(bucket); }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(path);
        try {
            return bean.resolveClient(s3).getObject(
                    GetObjectRequest.builder().bucket(s3.getBucket()).key(s3.getS3Key()).build(),
                    software.amazon.awssdk.core.async.AsyncResponseTransformer.toBlockingInputStream()
            ).join();
        } catch (Exception e) {
            throw new IOException("S3 read failed: " + s3, e);
        }
    }

    @Override
    public InputStream newRangeInputStream(Path path, long offset, long length) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(path);
        try {
            return bean.resolveClient(s3).getObject(
                    GetObjectRequest.builder()
                            .bucket(s3.getBucket()).key(s3.getS3Key())
                            .range("bytes=" + offset + "-" + (offset + length - 1)).build(),
                    software.amazon.awssdk.core.async.AsyncResponseTransformer.toBlockingInputStream()
            ).join();
        } catch (Exception e) {
            throw new IOException("S3 range read failed: " + s3, e);
        }
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(path);
        return new S3SmartOutputStream(s3, bean.resolveClient(s3),
                bean.resolveThreshold(), bean.resolvePartSize(), bean.posixService);
    }

    @Override
    public MultipartUploadHandle initiateMultipartUpload(Path path) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(path);
        try {
            CreateMultipartUploadResponse resp = bean.resolveClient(s3).createMultipartUpload(
                    CreateMultipartUploadRequest.builder()
                            .bucket(s3.getBucket()).key(s3.getS3Key()).build()
            ).join();
            return new S3MultipartUploadHandle(bean.resolveClient(s3), s3, resp.uploadId());
        } catch (Exception e) {
            throw new IOException("S3 multipart initiation failed: " + s3, e);
        }
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                               FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException("SeekableByteChannel not supported for S3.");
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
                                                     DirectoryStream.Filter<? super Path> filter) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3Dir = bean.toS3Path(dir);
        String prefix = s3Dir.getS3Key().isEmpty() ? ""
                : (s3Dir.getS3Key().endsWith("/") ? s3Dir.getS3Key() : s3Dir.getS3Key() + "/");
        try {
            ListObjectsV2Response resp = bean.resolveClient(s3Dir).listObjectsV2(
                    ListObjectsV2Request.builder()
                            .bucket(s3Dir.getBucket()).prefix(prefix).delimiter("/").build()
            ).join();

            List<Path> entries = new ArrayList<>();
            S3FileSystem fs = (S3FileSystem) bean.getFileSystem(URI.create("s3://" + s3Dir.getBucket()));
            resp.contents().forEach(obj -> {
                S3Path p = new S3Path(fs, "/" + obj.key());
                try { if (filter.accept(p)) entries.add(p); } catch (IOException ignore) {}
            });
            resp.commonPrefixes().forEach(cp -> {
                S3Path p = new S3Path(fs, "/" + cp.prefix());
                try { if (filter.accept(p)) entries.add(p); } catch (IOException ignore) {}
            });
            return new DirectoryStream<>() {
                public Iterator<Path> iterator() { return entries.iterator(); }
                public void close() {}
            };
        } catch (Exception e) {
            throw new IOException("S3 listing failed: " + s3Dir, e);
        }
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(dir);
        String key = s3.getS3Key().endsWith("/") ? s3.getS3Key() : s3.getS3Key() + "/";
        try {
            bean.resolveClient(s3).putObject(
                    PutObjectRequest.builder().bucket(s3.getBucket()).key(key).build(),
                    AsyncRequestBody.fromBytes(new byte[0])
            ).join();
            bean.posixService.saveDirectory(s3.toUri().toString());
        } catch (Exception e) {
            throw new IOException("S3 directory creation failed: " + s3, e);
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(path);
        try {
            bean.resolveClient(s3).deleteObject(
                    DeleteObjectRequest.builder().bucket(s3.getBucket()).key(s3.getS3Key()).build()
            ).join();
            bean.posixService.delete(s3.toUri().toString());
        } catch (Exception e) {
            throw new IOException("S3 deletion failed: " + s3, e);
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        if (source instanceof S3Path src && target instanceof S3Path tgt
                && src.getBucket().equals(tgt.getBucket())) {
            springBean().resolveClient(src).copyObject(CopyObjectRequest.builder()
                    .sourceBucket(src.getBucket()).sourceKey(src.getS3Key())
                    .destinationBucket(tgt.getBucket()).destinationKey(tgt.getS3Key())
                    .build()).join();
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
        throw new UnsupportedOperationException("FileStore not supported for S3");
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(path);
        try {
            bean.resolveClient(s3).headObject(HeadObjectRequest.builder()
                    .bucket(s3.getBucket()).key(s3.getS3Key()).build()).join();
        } catch (Exception e) {
            throw new NoSuchFileException(s3.toString());
        }
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                  LinkOption... options) { return null; }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                             LinkOption... options) throws IOException {
        S3FileSystemProvider bean = springBean();
        S3Path s3 = bean.toS3Path(path);
        try {
            HeadObjectResponse head = bean.resolveClient(s3).headObject(
                    HeadObjectRequest.builder().bucket(s3.getBucket()).key(s3.getS3Key()).build()
            ).join();
            String vp = s3.toUri().toString();
            var posix = bean.posixService.find(vp);
            return (A) new BasicFileAttributes() {
                public FileTime lastModifiedTime() { return FileTime.from(head.lastModified()); }
                public FileTime lastAccessTime() {
                    return posix.map(m -> FileTime.from(m.getAccessedAt())).orElse(lastModifiedTime());
                }
                public FileTime creationTime() {
                    return posix.map(m -> FileTime.from(m.getCreatedAt())).orElse(lastModifiedTime());
                }
                public boolean isRegularFile() { return true; }
                public boolean isDirectory()   { return false; }
                public boolean isSymbolicLink(){ return false; }
                public boolean isOther()       { return false; }
                public long size()             { return head.contentLength(); }
                public Object fileKey()        { return vp; }
            };
        } catch (Exception e) {
            throw new NoSuchFileException(s3.toString());
        }
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
        return Collections.emptyMap();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        S3FileSystemProvider bean = springBean();
        String vp = bean.toS3Path(path).toUri().toString();
        bean.posixService.setAttribute(vp, attribute, value);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private static S3MountProperties toS3Props(MountPointProperties props) {
        if (props instanceof S3MountProperties s3) return s3;
        S3MountProperties s3 = new S3MountProperties();
        s3.setPath(props.getPath());
        s3.setMultipartThreshold(props.getMultipartThreshold());
        s3.setPartSize(props.getPartSize());
        return s3;
    }

    private S3AsyncClient buildClient(S3MountProperties props) {
        AwsCredentialsProvider cp = (props.getAccessKey() != null && props.getSecretKey() != null)
                ? StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()))
                : DefaultCredentialsProvider.create();

        var builder = S3AsyncClient.builder()
                .region(Region.of(props.getRegion() != null ? props.getRegion() : "us-east-1"))
                .credentialsProvider(cp);

        if (props.getEndpoint() != null)
            builder.endpointOverride(URI.create(props.getEndpoint())).forcePathStyle(true);

        return builder.build();
    }

    private S3Path toS3Path(Path path) {
        if (path instanceof S3Path s3) return s3;
        return (S3Path) getPath(path.toUri());
    }

    private S3AsyncClient resolveClient(S3Path path) {
        return clientsByMount.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No S3 client configured."));
    }

    private long resolveThreshold() {
        return mountConfigs.values().stream()
                .filter(m -> m.getMultipartThreshold() != null)
                .map(MountPointProperties::getMultipartThreshold)
                .findFirst().orElse(defaultChunkSize);
    }

    private long resolvePartSize() {
        return mountConfigs.values().stream()
                .filter(m -> m.getPartSize() != null)
                .map(MountPointProperties::getPartSize)
                .findFirst().orElse(defaultChunkSize);
    }
}
