package edu.m4z.storage.nfs;

import edu.m4z.storage.core.fs.StorageBackend;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NfsBackend implements StorageBackend {
    private static final Logger log = LoggerFactory.getLogger(NfsBackend.class);
    private static final int BUF = 8 * 1024 * 1024;
    private final Map<String, Path> roots = new ConcurrentHashMap<>();

    @Override
    public String scheme() {
        return "nfs";
    }

    @Override
    public void initBucket(String alias, Map<String, String> cfg) {
        String r = cfg.getOrDefault("root", "/mnt/nfs/" + alias);
        roots.put(alias, Path.of(r));
        log.info("NFS '{}' -> {}", alias, r);
    }

    private Path real(String a, String k) {
        Path root = roots.get(a);
        return k.isEmpty() ? root : root.resolve(k);
    }

    @Override
    public InputStream newInputStream(String a, String k) throws IOException {
        Path p = real(a, k);
        if (!Files.exists(p)) throw new NoSuchFileException(p.toString());
        return new BufferedInputStream(Files.newInputStream(p), BUF);
    }

    @Override
    public OutputStream newOutputStream(String a, String k) throws IOException {
        Path p = real(a, k);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        return new BufferedOutputStream(Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), BUF);
    }

    @Override
    public BasicFileAttributes readAttributes(String a, String k) throws IOException {
        return Files.readAttributes(real(a, k), BasicFileAttributes.class);
    }

    @Override
    public boolean exists(String a, String k) {
        return Files.exists(real(a, k));
    }

    @Override
    public void delete(String a, String k) throws IOException {
        Files.delete(real(a, k));
    }

    @Override
    public List<String> list(String a, String prefix) throws IOException {
        Path d = real(a, prefix);
        List<String> e = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(d)) {
            for (Path p : s) e.add(p.getFileName().toString());
        }
        return e;
    }

    @Override
    public void createDirectory(String a, String k) throws IOException {
        Files.createDirectories(real(a, k));
    }

    @Override
    public void copy(String sa, String sk, String da, String dk) throws IOException {
        Path s = real(sa, sk), d = real(da, dk);
        if (d.getParent() != null) Files.createDirectories(d.getParent());
        Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
    }
}
