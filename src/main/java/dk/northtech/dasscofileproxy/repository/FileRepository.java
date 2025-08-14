package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.DasscoFile;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface FileRepository {
    static final String INSERT = """
            INSERT INTO file(asset_guid, size_bytes, path, crc) VALUES (:assetGuid, :sizeBytes, :path, :crc)
            """;
    @SqlUpdate(INSERT)
    @GetGeneratedKeys
    public long insertFile(@BindMethods DasscoFile dasscoFile);


    @SqlQuery("SELECT * FROM file WHERE asset_guid = :assetGuid")
    List<DasscoFile> getFilesByAssetGuid(@Bind String assetGuid);

    @SqlQuery("SELECT * FROM file WHERE asset_guid = :assetGuid AND sync_status = 'SYNCHRONIZED'")
    List<DasscoFile> getSyncFilesByAssetGuid(@Bind String assetGuid);

    @SqlQuery("SELECT * FROM file WHERE path = :path AND delete_after_sync = FALSE ")
    DasscoFile getFilesByAssetPath(@Bind String path);

    @SqlUpdate("DELETE FROM file WHERE asset_guid = :assetGuid")
    void deleteFilesByAssetGuid(@Bind String assetGuid);

    @SqlUpdate("DELETE FROM file WHERE delete_after_sync = TRUE AND asset_guid = :assetGuid")
    void deleteFilesMarkedForDeletionByAssetGuid(@Bind String assetGuid);

    // For undoing all local changes without syncing to ERDA.
    @SqlUpdate("DELETE FROM file WHERE asset_guid = :assetGuid AND sync_status = 'NEW_FILE'")
    void deleteNewFiles(@Bind String assetGuid);

    // For undoing all local changes without syncing to ERDA.
    @SqlUpdate("UPDATE file SET delete_after_sync = false WHERE asset_guid = :assetGuid AND sync_status = 'SYNCHRONIZED'")
    void resetDeleteFlag(@Bind String assetGuid);

    @SqlUpdate("DELETE FROM file WHERE file_id = :fileId AND delete_after_sync = TRUE")
    void deleteFile(@Bind String assetGuid);

    @SqlUpdate("UPDATE file SET delete_after_sync = true WHERE path = :path")
    void markForDeletion(@Bind String path);

    @SqlUpdate("UPDATE file SET sync_status = 'SYNCHRONIZED' WHERE asset_guid = :assetGuid AND sync_status = 'NEW_FILE'")
    void setSynchronizedStatus(@Bind String assetGuid);

    @SqlQuery("SELECT sum(size_bytes) AS totalAllocated FROM file WHERE asset_guid IN (<asset_guids>) AND delete_after_sync = FALSE")
    long getTotalAllocatedByAsset(@BindList Set<String> asset_guids);

     @SqlQuery("""
        select f.* from collection c
        inner join asset a on a.collection_id = c.collection_id
        inner join file f on f.asset_guid = a.asset_guid
        where
            c.institution_name = :institution and
            c.collection_name = :collection and
            f.path ilike '%' || :filename and
            f.has_thumbnail = :hasThumbnail
    """)
     Optional<DasscoFile> getFilePathForAdapterFile(@Bind String institution, @Bind String collection, @Bind String filename, @Bind boolean hasThumbnail);
}
