package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.CacheInfo;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

public interface FileCacheRepository {

    @SqlQuery("""
    SELECT fc.file_cache_id
            , fc.file_id  
            , fc.expiration_datetime
            , fc.creation_datetime
            , f.path
            , f.size_bytes
            , f.sync_status
            , f.delete_after_sync
            FROM file_cache fc
    LEFT JOIN files f ON f.file_id = fc.file_id 
    WHERE f.path = :path
    """)
    Optional<CacheInfo> getFileCacheByPath(@Bind String path);

    @SqlUpdate("""
            INSERT INTO file_cache(creation_datetime, expiration_datetime, file_id)
            VALUES (now(), :expiration_datetime, :fileId)
            """)
    void insertCache(@BindMethods CacheInfo cacheInfo);

    @SqlUpdate("""
            UPDATE file_cache f SET expiration_datetime = now() 
            WHERE f.file_cache_id IN (<ids>)
            """)
    void refreshCacheEntries(@BindList List<Long> ids);

    @SqlQuery("""
            DELETE FROM public.file_cache fic
            USING
            (
            	SELECT subq.file_cache_id, subq.path FROM (
            		SELECT fc.*
            		, f.path
             		, f.size_bytes
             		, sum(f.size_bytes) OVER (ORDER BY expiration_datetime DESC) AS cummulative_size
            		FROM public.file_cache fc INNER JOIN public.files f
            		ON f.file_id  = fc.file_id
            	) subq  -- If cache is bigger than specified limit, additional entries should also be evicted.
            	WHERE subq.expiration_datetime < now() OR cummulative_size > :maxMemoryBytes
            	) cache_id_path	
            WHERE fic.file_cache_id = cache_id_path.file_cache_id
            RETURNING cache_id_path.path
                        """)
    List<String> evict(long maxMemoryBytes);
}
