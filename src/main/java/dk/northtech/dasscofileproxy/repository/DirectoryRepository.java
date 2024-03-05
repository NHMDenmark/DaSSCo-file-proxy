package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.AssetUpdate;
import dk.northtech.dasscofileproxy.domain.Directory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface DirectoryRepository {
    static final String INSERT = """
            INSERT INTO directories(uri, node_host , access, allocated_storage_mb ,creation_datetime)
                                    VALUES(:uri, :node_host ,:access::access_type, :allocatedStorageMb, :creationDatetime)
            """;
    @SqlUpdate(INSERT)
    @GetGeneratedKeys
    public long insertDirectory(@BindMethods Directory directory);
//TODO check if this returns id.
    //Find a directory by assetGuid. Should only return one as only single asset directories will have write access.
    @SqlQuery("""
        SELECT d.* FROM directories d 
            LEFT JOIN shared_assets sa ON sa.directory_id = d.directory_id 
        WHERE d.access = 'WRITE'::access_type AND sa.asset_guid = :assetGuid 
""")
    List<Directory> getWriteableDirectoriesByAsset(@Bind String assetGuid);

    @SqlQuery("SELECT * FROM directories WHERE directory_id = :directoryId")
    Directory getDirectory(@Bind long directoryId);

    @SqlUpdate("DELETE FROM directories WHERE directory_id = :directoryId")
    void deleteSharedAsset(@Bind long directoryId);

    @SqlUpdate("UPDATE directories SET allocated_storage_mb = :newAllocation WHERE directory_id = :directoryId")
    void updateAllocatedStorage(@Bind long directoryId, int newAllocation);

    @SqlQuery("SELECT sum(allocated_storage_mb) as totalAllocated FROM directories")
    int getTotalAllocated();

    @SqlUpdate("UPDATE directories SET awaiting_erda_sync = TRUE,  sync_user = :digitiser, sync_workstation = :workstation, sync_pipeline = :pipeline, erda_sync_attempts = 0 where directory_id = :directoryId")
    void scheduleDiretoryForSynchronization(long directoryId,  @BindMethods AssetUpdate assetUpdate);

    //This should look for node host in future
    @SqlQuery("UPDATE directories SET erda_sync_attempts = erda_sync_attempts + 1 WHERE awaiting_erda_sync = true AND erda_sync_attempts < :maxAttempts RETURNING *")
    List<Directory> getDirectoriesForSynchronization(int maxAttempts);
}
