package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.SharedAsset;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Collection;
import java.util.List;

public interface SharedAssetRepository {
    @SqlBatch("INSERT INTO shared_assets(directory_id, asset_guid, creation_datetime) VALUES(:newDirectoryId, :assetGuid, :creationDatetime)")
    void fillBatch(@Bind Long newDirectoryId, @BindMethods Collection<SharedAsset> sharedAssets);

    @SqlQuery("SELECT count(1) FROM shared_assets")
    int countBatch();

    @SqlQuery("SELECT * FROM shared_assets WHERE directory_id = :directoryId")
    List<SharedAsset> getSharedAssetsByDirectory(@Bind long directoryId);

    @SqlUpdate("DELETE FROM shared_assets WHERE directory_id = :directoryId")
    void deleteSharedAsset(@Bind long directoryId);

    @SqlQuery("SELECT * FROM shared_assets")
    List<SharedAsset> getSharedAssets();

}
