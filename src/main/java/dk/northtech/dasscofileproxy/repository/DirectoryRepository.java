package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.Directory;
import dk.northtech.dasscofileproxy.domain.SharedAsset;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface DirectoryRepository {
    static final String INSERT = """
            INSERT INTO directory(uri, access, allocated_mb ,creation_datetime)
                                    VALUES(:uri, :access::access_type, :allocatedMb, :creationDatetime)
            """;
    @SqlUpdate(INSERT)
    @GetGeneratedKeys
    public long insertDirectory(@BindMethods Directory directory);

    @SqlQuery("SELECT * FROM directories")
    List<Directory> getAllDirectories(@Bind long directoryId);

    @SqlQuery("SELECT * FROM directories WHERE directory_id = :directoryId")
    Directory getDirectorie(@Bind long directoryId);

    @SqlUpdate("DELETE FROM directories WHERE directory_id = :directoryId")
    void deleteSharedAsset(@Bind long directoryId);

    @SqlQuery("SELECT sum(allocated_mb) as totalAllocated FROM directories")
    int getTotalAllocated();
}
