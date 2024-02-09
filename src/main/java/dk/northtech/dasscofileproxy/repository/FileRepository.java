package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.DasscoFile;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface FileRepository {
    static final String INSERT = """
            INSERT INTO files(asset_guid, size_bytes, path, crc) VALUES (:assetGuid, :sizeBytes, :path, :crc)
            """;
    @SqlUpdate(INSERT)
    @GetGeneratedKeys
    public long insertFile(@BindMethods DasscoFile dasscoFile);

//    @SqlQuery("SELECT * FROM directories")
//    List<Directory> getAllDirectories(@Bind long directoryId);

    @SqlQuery("SELECT * FROM files WHERE asset_guid = :assetGuid")
    List<DasscoFile> getFilesByAssetGuid(@Bind String assetGuid);

    @SqlQuery("SELECT * FROM files WHERE path = :path AND delete_after_sync = FALSE ")
    List<DasscoFile> getFilesByAssetPath(@Bind String path);

    @SqlUpdate("DELETE FROM files WHERE asset_guid = :assetGuid")
    void deleteFilesByAssetGuid(@Bind String assetGuid);

    @SqlUpdate("DELETE FROM files WHERE file_id = :fileId AND delete_after_sync = TRUE")
    void deleteFile(@Bind String assetGuid);

    @SqlUpdate("UPDATE files SET delete_after_sync = true WHERE path = :path")
    void markForDeletion(@Bind String path);

    @SqlQuery("SELECT sum(size_bytes) as totalAllocated FROM files WHERE asset_guid = :assetGuid AND delete_after_sync = FALSE")
    long getTotalAllocatedByAsset(String assetGuid);
}
