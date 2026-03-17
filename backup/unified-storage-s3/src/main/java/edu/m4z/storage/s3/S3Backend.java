package edu.m4z.storage.s3;

import org.slf4j.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class S3Backend implements StorageBackend {
    private static final Logger log = LoggerFactory.getLogger(S3Backend.class);
    private static final int CHUNK = 8 * 1024 * 1024;
    private final Map<String, S3Client> clients = new ConcurrentHashMap<>();

    @Override
    public String scheme() {
        return "s3";
    }

    @Override
    public void initBucket(String bucket, Map<String, String> cfg) {
        clients.computeIfAbsent(bucket, b -> {
            S3ClientBuilder bl = S3Client.builder();
            if (cfg.get("region") != null) bl.region(Region.of(cfg.get("region")));
            if (cfg.get("endpoint") != null) bl.endpointOverride(URI.create(cfg.get("endpoint")));
            if (cfg.get("accessKey") != null && cfg.get("secretKey") != null)
                bl.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.get("accessKey"), cfg.get("secretKey"))));
            log.info("S3 client for '{}' region={} endpoint={}", b, cfg.get("region"), cfg.get("endpoint"));
            return bl.build();
        });
    }

    private S3Client c(String b) {
        return clients.get(b);
    }

    @Override
    public InputStream newInputStream(String bucket, String key) throws IOException {
        long sz;
        try {
            sz = c(bucket).headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException("s3://" + bucket + "/" + key);
        } catch (S3Exception e) {
            throw new IOException(e);
        }
        if (sz <= CHUNK) return c(bucket).getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        return new ChunkedInputStream(sz, CHUNK, (off, len) -> {
            try {
                return c(bucket).getObject(GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=" + off + "-" + (off + len - 1)).build());
            } catch (S3Exception e) {
                throw new UncheckedIOException(new IOException(e));
            }
        });
    }

    @Override
    public OutputStream newOutputStream(String bucket, String key) throws IOException {
        var init = c(bucket).createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());
        String uid = init.uploadId();
        List<CompletedPart> parts = Collections.synchronizedList(new ArrayList<>());
        return new ChunkedOutputStream(CHUNK,
                ch -> {
                    try {
                        var r = c(bucket).uploadPart(UploadPartRequest.builder().bucket(bucket).key(key).uploadId(uid).partNumber(ch.partNumber()).build(), RequestBody.fromBytes(ch.bytes()));
                        parts.add(CompletedPart.builder().partNumber(ch.partNumber()).eTag(r.eTag()).build());
                    } catch (S3Exception e) {
                        throw new UncheckedIOException(new IOException(e));
                    }
                },
                () -> {
                    try {
                        c(bucket).completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uid).multipartUpload(CompletedMultipartUpload.builder().parts(parts.stream().sorted(Comparator.comparingInt(CompletedPart::partNumber)).toList()).build()).build());
                    } catch (S3Exception e) {
                        throw new UncheckedIOException(new IOException(e));
                    }
                },
                () -> {
                    try {
                        c(bucket).abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uid).build());
                    } catch (S3Exception e) {
                        log.error("abort fail", e);
                    }
                }
        );
    }

    @Override
    public BasicFileAttributes readAttributes(String b, String k) throws IOException {
        if (k.isEmpty()) return StorageFileAttributes.directory();
        try {
            var h = c(b).headObject(HeadObjectRequest.builder().bucket(b).key(k).build());
            return new StorageFileAttributes(h.contentLength(), h.lastModified(), false);
        } catch (NoSuchKeyException e) {
            String p = k.endsWith("/") ? k : k + "/";
            var l = c(b).listObjectsV2(ListObjectsV2Request.builder().bucket(b).prefix(p).maxKeys(1).build());
            if (!l.contents().isEmpty() || !l.commonPrefixes().isEmpty()) return StorageFileAttributes.directory();
            throw new NoSuchFileException("s3://" + b + "/" + k);
        } catch (S3Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean exists(String b, String k) {
        try {
            readAttributes(b, k);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void delete(String b, String k) throws IOException {
        c(b).deleteObject(DeleteObjectRequest.builder().bucket(b).key(k).build());
    }

    @Override
    public List<String> list(String b, String prefix) throws IOException {
        String p = prefix.isEmpty() ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        var r = c(b).listObjectsV2(ListObjectsV2Request.builder().bucket(b).prefix(p).delimiter("/").build());
        List<String> e = new ArrayList<>();
        for (var cp : r.commonPrefixes()) {
            String n = cp.prefix().substring(p.length());
            if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
            if (!n.isEmpty()) e.add(n);
        }
        for (var o : r.contents()) {
            String n = o.key().substring(p.length());
            if (!n.isEmpty() && !n.equals("/")) e.add(n);
        }
        return e;
    }

    @Override
    public void createDirectory(String b, String k) throws IOException {
        c(b).putObject(PutObjectRequest.builder().bucket(b).key(k.endsWith("/") ? k : k + "/").build(), RequestBody.empty());
    }

    @Override
    public void copy(String sb, String sk, String db, String dk) throws IOException {
        c(db).copyObject(CopyObjectRequest.builder().sourceBucket(sb).sourceKey(sk).destinationBucket(db).destinationKey(dk).build());
    }
}
