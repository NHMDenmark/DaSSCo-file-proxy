package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.SambaServer;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface SambaServerRepository {
        @SqlQuery("SELECT * FROM samba_servers WHERE samba_server_id = :sambaServerId")
        SambaServer getSambaServer(@Bind long sambaServerId);

        @SqlUpdate("DELETE FROM samba_servers WHERE samba_server_id = :sambaServerId")
        void deleteServer(@Bind long sambaServerId);

}
