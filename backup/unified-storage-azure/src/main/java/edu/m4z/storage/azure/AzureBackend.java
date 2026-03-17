package edu.m4z.storage.azure;

import com.azure.storage.blob.*;
import com.azure.storage.blob.models.*;
import edu.m4z.storage.core.io.ChunkedInputStream;
import org.slf4j.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AzureBackend implements StorageBackend {
    private static final Logger log = LoggerFactory.getLogger(AzureBackend.class);
    private static final int CHUNK = 8 * 1024 * 1024;
    private final Map<String, BlobServiceClient> clients = new ConcurrentHashMap<>();

    @Override
    public String scheme() {
        return "azblob";
    }

    @Override
    public void initBucket(String container, Map<String, String> cfg) {
        clients.computeIfAbsent(container, c -> {
            String cs = cfg.get("connectionString");
            if (cs == null) cs = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
            if (cs == null && cfg.get("accountName") != null && cfg.get("accountKey") != null) cs = "DefaultEndpointsProtocol=https;AccountName=" + cfg.get("accountName") + ";AccountKey=" + cfg.get("accountKey") + ";EndpointSuffix=core.windows.net";
            if (cs == null) throw new IllegalStateException("Azure '" + c + "': no connection config");
            var bl = new BlobServiceClientBuilder().connectionString(cs);
            if (cfg.get("endpoint") != null) bl.endpoint(cfg.get("endpoint"));
            log.info("Azure client for '{}'", c);
            return bl.buildClient();
        });
    }

    private BlobClient blob(String c, String k) {
        return clients.get(c).getBlobContainerClient(c).getBlobClient(k);
    }

    @Override
    public InputStream newInputStream(String c, String k) throws IOException {
        BlobClient bc = blob(c, k);
        long sz;
        try {
            sz = bc.getProperties().getBlobSize();
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) throw new NoSuchFileException("azblob://" + c + "/" + k);
            throw new IOException(e);
        }
        if (sz <= CHUNK) return bc.openInputStream();
        return new ChunkedInputStream(sz, CHUNK, (off, len) -> {
            try {
                return bc.openInputStream(new BlobInputStreamOptions().setRange(new BlobRange(off, (long) len)));
            } catch (BlobStorageException e) {
                throw new UncheckedIOException(new IOException(e));
            }
        });
    }

    @Override
    public OutputStream newOutputStream(String c, String k) throws IOException {
        return blob(c, k).getBlockBlobClient().getBlobOutputStream(true);
    }

    @Override
    public BasicFileAttributes readAttributes(String c, String k) throws IOException {
        if (k.isEmpty()) return StorageFileAttributes.directory();
        try {
            var p = blob(c, k).getProperties();
            return new StorageFileAttributes(p.getBlobSize(), p.getLastModified() != null ? p.getLastModified().toInstant() : null, false);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() != 404) throw new IOException(e);
        }
        String p = k.endsWith("/") ? k : k + "/";
        if (clients.get(c).getBlobContainerClient(c).listBlobs(new ListBlobsOptions().setPrefix(p), null).iterator().hasNext()) return StorageFileAttributes.directory();
        throw new NoSuchFileException("azblob://" + c + "/" + k);
    }

    @Override
    public boolean exists(String c, String k) {
        try {
            readAttributes(c, k);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void delete(String c, String k) throws IOException {
        try {
            blob(c, k).delete();
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) throw new NoSuchFileException("azblob://" + c + "/" + k);
            throw new IOException(e);
        }
    }

    @Override
    public List<String> list(String c, String prefix) throws IOException {
        String p = prefix.isEmpty() ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        List<String> e = new ArrayList<>();
        for (BlobItem i : clients.get(c).getBlobContainerClient(c).listBlobsByHierarchy(p)) {
            String n = i.getName().substring(p.length());
            if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
            if (!n.isEmpty()) e.add(n);
        }
        return e;
    }

    @Override
    public void createDirectory(String c, String k) {
    }

    @Override
    public void copy(String sc, String sk, String dc, String dk) throws IOException {
        blob(dc, dk).beginCopy(blob(sc, sk).getBlobUrl(), null).waitForCompletion();
    }
}
