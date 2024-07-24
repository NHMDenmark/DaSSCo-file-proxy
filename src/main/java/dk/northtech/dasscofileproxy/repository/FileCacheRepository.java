package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.CacheInfo;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

public interface FileCacheRepository {

    @SqlQuery("SELECT * FROM file_cache WHERE asset_guid = :assetGuid")
    CacheInfo getFilesByAssetGuid(@Bind String assetGuid);

//    @SqlQuery("SELECT * FROM file_cache WHERE asset_guid = :assetGuid")
//    List<DasscoFile> getFilesByAssetGuid(@Bind String assetGuid);
}
