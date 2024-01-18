package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.SambaServer;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface DictionaryRepository {
        @SqlQuery("SELECT * FROM dictionary WHERE dictionary_id = :dictionaryId")
        SambaServer getSambaServer(@Bind long dictionaryId);

        @SqlUpdate("DELETE FROM samba_servers WHERE dictionary_id = :dictionaryId")
        void deleteServer(@Bind long dictionaryId);
        }
