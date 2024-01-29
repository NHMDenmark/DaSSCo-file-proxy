package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.UserAccess;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Collection;
import java.util.List;

public interface UserAccessList {
    @SqlBatch("INSERT INTO user_access(directory_id, username, token, creation_datetime) VALUES (:newDirectoryId, :username, :token, :creationDatetime)")
    void fillBatch(@Bind Long newDirectoryId, @BindMethods Collection<UserAccess> userAccess);

    @SqlQuery("SELECT count(1) FROM shared_assets")
    int countBatch();

    @SqlQuery("SELECT * FROM user_access WHERE directory_id = :directoryId")
    List<UserAccess> getUserAccess(@Bind long directoryId);

    @SqlUpdate("DELETE FROM user_access WHERE directory_id = :directoryId")
    void deleteUserAccess(@Bind long directoryId);
}