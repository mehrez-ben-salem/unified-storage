package edu.m4z.storage.core;

import java.util.HashMap;
import java.util.Map;

/**
 * One mount from application.yml.
 * <pre>{@code
 * unified.storage.mounts:
 *   - path: /data/reports
 *     uri: s3://reports-bucket
 *     prefix: reports/
 *     region: eu-west-1
 *     access-key: ${S3_ACCESS_KEY}
 *     secret-key: ${S3_SECRET_KEY}
 * }</pre>
 */
public class MountPoint {

    private String path;
    private String uri;
    private String prefix = "";
    // S3
    private String region;
    private String endpoint;
    private String accessKey;
    private String secretKey;
    // GCS
    private String projectId;
    private String credentialsPath;
    // Azure
    private String connectionString;
    private String accountName;
    private String accountKey;
    // NFS
    private String root;

    public String scheme() {
        if (uri == null) return "nfs";
        int i = uri.indexOf("://");
        return i > 0 ? uri.substring(0, i) : "nfs";
    }

    public String bucket() {
        if (uri == null) return "default";
        int i = uri.indexOf("://");
        if (i < 0) return uri;
        String r = uri.substring(i + 3);
        int s = r.indexOf('/');
        return s > 0 ? r.substring(0, s) : r;
    }

    public String mountPath() {
        if (path == null || path.isEmpty()) return "/";
        String p = path.startsWith("/") ? path : "/" + path;
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    public String normalizedPrefix() {
        if (prefix == null || prefix.isEmpty()) return "";
        String p = prefix.startsWith("/") ? prefix.substring(1) : prefix;
        return p.endsWith("/") ? p : p + "/";
    }

    /**
     * Resolve relative path to key within bucket
     */
    public String toKey(String relativePath) {
        String r = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return normalizedPrefix() + r;
    }

    /**
     * Build config map for the backend
     */
    public Map<String, String> toConfigMap() {
        Map<String, String> m = new HashMap<>();
        if (region != null) m.put("region", region);
        if (endpoint != null) m.put("endpoint", endpoint);
        if (accessKey != null) m.put("accessKey", accessKey);
        if (secretKey != null) m.put("secretKey", secretKey);
        if (projectId != null) m.put("projectId", projectId);
        if (credentialsPath != null) m.put("credentialsPath", credentialsPath);
        if (connectionString != null) m.put("connectionString", connectionString);
        if (accountName != null) m.put("accountName", accountName);
        if (accountKey != null) m.put("accountKey", accountKey);
        if (root != null) m.put("root", root);
        return m;
    }

    // Getters/Setters
    public String getPath() {
        return path;
    }

    public void setPath(String v) {
        this.path = v;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String v) {
        this.uri = v;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String v) {
        this.prefix = v;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String v) {
        this.region = v;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String v) {
        this.endpoint = v;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String v) {
        this.accessKey = v;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String v) {
        this.secretKey = v;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String v) {
        this.projectId = v;
    }

    public String getCredentialsPath() {
        return credentialsPath;
    }

    public void setCredentialsPath(String v) {
        this.credentialsPath = v;
    }

    public String getConnectionString() {
        return connectionString;
    }

    public void setConnectionString(String v) {
        this.connectionString = v;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String v) {
        this.accountName = v;
    }

    public String getAccountKey() {
        return accountKey;
    }

    public void setAccountKey(String v) {
        this.accountKey = v;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String v) {
        this.root = v;
    }

    @Override
    public String toString() {
        return mountPath() + " → " + uri;
    }
}
