package dk.northtech.dasscofileproxy.repository;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface ActiveLargeUploadRepository {
    @SqlUpdate("""
            INSERT INTO active_large_uploads(upload_id, asset_guid, directory_id, path)
            VALUES (:uploadId, :assetGuid, :directoryId, :path)
            """)
    void insert(@Bind String uploadId, @Bind String assetGuid, @Bind long directoryId, @Bind String path);

    @SqlUpdate("DELETE FROM active_large_uploads WHERE upload_id = :uploadId")
    void delete(@Bind String uploadId);

    @SqlUpdate("DELETE FROM active_large_uploads WHERE asset_guid = :assetGuid")
    void deleteByAssetGuid(@Bind String assetGuid);

    @SqlQuery("SELECT EXISTS(SELECT 1 FROM active_large_uploads WHERE directory_id = :directoryId)")
    boolean existsByDirectoryId(@Bind long directoryId);
}
