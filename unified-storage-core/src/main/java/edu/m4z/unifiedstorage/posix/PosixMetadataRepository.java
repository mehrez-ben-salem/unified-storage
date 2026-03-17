package edu.m4z.unifiedstorage.posix;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for POSIX metadata persistence.
 */
@Repository
public interface PosixMetadataRepository extends JpaRepository<PosixMetadata, String> {

    Optional<PosixMetadata> findByVirtualPath(String virtualPath);

    /** Directory listing: all direct children. */
    List<PosixMetadata> findByParentPath(String parentPath);

    /** Recursive listing: all descendants under a given prefix. */
    @Query("SELECT m FROM PosixMetadata m WHERE m.virtualPath LIKE :prefix%")
    List<PosixMetadata> findByVirtualPathStartingWith(@Param("prefix") String prefix);

    void deleteByVirtualPath(String virtualPath);

    /** Recursive deletion of a directory and all its contents. */
    @Modifying
    @Query("DELETE FROM PosixMetadata m WHERE m.virtualPath LIKE :prefix%")
    void deleteByVirtualPathStartingWith(@Param("prefix") String prefix);

    /** Update file size after a write. */
    @Modifying
    @Query("UPDATE PosixMetadata m SET m.size = :size, m.modifiedAt = CURRENT_TIMESTAMP WHERE m.virtualPath = :path")
    void updateSize(@Param("path") String path, @Param("size") long size);
}
