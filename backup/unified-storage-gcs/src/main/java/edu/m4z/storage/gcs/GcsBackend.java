package edu.m4z.storage.gcs;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;
import com.google.cloud.storage.Storage.BlobListOption;
import edu.m4z.storage.core.io.ChunkedInputStream;
import org.slf4j.*;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GcsBackend implements StorageBackend {
    private static final Logger log = LoggerFactory.getLogger(GcsBackend.class);
    private static final int CHUNK = 8 * 1024 * 1024;
    private final Map<String, com.google.cloud.storage.Storage> clients = new ConcurrentHashMap<>();

    @Override
    public String scheme() {
        return "gs";
    }

    @Override
    public void initBucket(String bucket, Map<String, String> cfg) {
        clients.computeIfAbsent(bucket, b -> {
            var bl = StorageOptions.newBuilder();
            if (cfg.get("projectId") != null) bl.setProjectId(cfg.get("projectId"));
            if (cfg.get("credentialsPath") != null) {
                try (var f = new FileInputStream(cfg.get("credentialsPath"))) {
                    bl.setCredentials(ServiceAccountCredentials.fromStream(f));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            if (cfg.get("endpoint") != null) bl.setHost(cfg.get("endpoint"));
            log.info("GCS client for '{}'", b);
            return bl.build().getService();
        });
    }

    private com.google.cloud.storage.Storage c(String b) {
        return clients.get(b);
    }

    @Override
    public InputStream newInputStream(String b, String k) throws IOException {
        Blob blob = c(b).get(BlobId.of(b, k));
        if (blob == null || !blob.exists()) throw new NoSuchFileException("gs://" + b + "/" + k);
        long sz = blob.getSize();
        if (sz <= CHUNK) return Channels.newInputStream(blob.reader());
        return new ChunkedInputStream(sz, CHUNK, (off, len) -> {
            try {
                ReadChannel rc = blob.reader();
                rc.seek(off);
                rc.limit(off + len);
                return Channels.newInputStream(rc);
            } catch (Exception e) {
                throw new UncheckedIOException(new IOException(e));
            }
        });
    }

    @Override
    public OutputStream newOutputStream(String b, String k) throws IOException {
        return Channels.newOutputStream(c(b).writer(BlobInfo.newBuilder(BlobId.of(b, k)).build()));
    }

    @Override
    public BasicFileAttributes readAttributes(String b, String k) throws IOException {
        if (k.isEmpty()) return StorageFileAttributes.directory();
        Blob bl = c(b).get(BlobId.of(b, k));
        if (bl != null && bl.exists()) return new StorageFileAttributes(bl.getSize(), bl.getUpdateTimeOffsetDateTime() != null ? bl.getUpdateTimeOffsetDateTime().toInstant() : null, false);
        String p = k.endsWith("/") ? k : k + "/";
        if (c(b).list(b, BlobListOption.prefix(p), BlobListOption.pageSize(1)).getValues().iterator().hasNext()) return StorageFileAttributes.directory();
        throw new NoSuchFileException("gs://" + b + "/" + k);
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
        if (!c(b).delete(BlobId.of(b, k))) throw new NoSuchFileException("gs://" + b + "/" + k);
    }

    @Override
    public List<String> list(String b, String prefix) throws IOException {
        String p = prefix.isEmpty() ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        List<String> e = new ArrayList<>();
        for (Blob bl : c(b).list(b, BlobListOption.prefix(p), BlobListOption.currentDirectory()).iterateAll()) {
            String n = bl.getName().substring(p.length());
            if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
            if (!n.isEmpty()) e.add(n);
        }
        return e;
    }

    @Override
    public void createDirectory(String b, String k) throws IOException {
        c(b).create(BlobInfo.newBuilder(BlobId.of(b, k.endsWith("/") ? k : k + "/")).build(), new byte[0]);
    }

    @Override
    public void copy(String sb, String sk, String db, String dk) throws IOException {
        c(db).copy(com.google.cloud.storage.Storage.CopyRequest.newBuilder().setSource(BlobId.of(sb, sk)).setTarget(BlobId.of(db, dk)).build()).getResult();
    }
}
