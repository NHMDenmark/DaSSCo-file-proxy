package dk.northtech.dasscofileproxy.repository;

import dk.northtech.dasscofileproxy.domain.SambaServer;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface SambaServerRepository {
        @SqlQuery("SELECT * FROM samba_servers WHERE samba_server_id = :sambaServerId")
        SambaServer getSambaServer(@Bind long sambaServerId);

        @SqlUpdate("DELETE FROM samba_servers WHERE samba_server_id = :sambaServerId")
        void deleteServer(@Bind long sambaServerId);

        @SqlUpdate("UPDATE samba_servers SET shared = :shared WHERE samba_server_id = :sambaServerId ")
        void updateShared(@Bind  boolean shared, @Bind long sambaServerId);

}
