-- V1__create_storage_posix_metadata.sql
-- Émulation POSIX pour les cloud object stores (S3, GCS)

CREATE TABLE storage_posix_metadata (
    id            VARCHAR(36)    NOT NULL,
    virtual_path  VARCHAR(2048)  NOT NULL,
    parent_path   VARCHAR(2048),
    name          VARCHAR(512),
    owner         VARCHAR(255),
    grp           VARCHAR(255),
    permissions   INTEGER        NOT NULL DEFAULT 420,  -- 0644 en décimal
    created_at    TIMESTAMP,
    modified_at   TIMESTAMP,
    accessed_at   TIMESTAMP,
    size          BIGINT         NOT NULL DEFAULT 0,
    is_directory  BOOLEAN        NOT NULL DEFAULT FALSE,
    link_target   VARCHAR(2048),
    CONSTRAINT pk_posix_metadata PRIMARY KEY (id),
    CONSTRAINT uq_posix_virtual_path UNIQUE (virtual_path)
);

-- Index sur virtual_path (lookup principal)
CREATE INDEX idx_posix_virtual_path ON storage_posix_metadata (virtual_path);

-- Index sur parent_path (listing répertoire)
CREATE INDEX idx_posix_parent_path ON storage_posix_metadata (parent_path);

-- Index préfixe pour findByVirtualPathStartingWith (listing récursif)
CREATE INDEX idx_posix_path_prefix ON storage_posix_metadata (virtual_path);

COMMENT ON TABLE  storage_posix_metadata IS 'Métadonnées POSIX émulées pour les fichiers cloud (S3/GCS)';
COMMENT ON COLUMN storage_posix_metadata.virtual_path IS 'URI complète du fichier: s3://bucket/path/file.csv';
COMMENT ON COLUMN storage_posix_metadata.permissions  IS 'Permissions POSIX en décimal (ex: 420 = 0644 octal)';
COMMENT ON COLUMN storage_posix_metadata.is_directory IS 'true pour les répertoires virtuels (préfixe S3/GCS)';
