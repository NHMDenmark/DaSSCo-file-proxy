package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.SambaServer;
import dk.northtech.dasscofileproxy.domain.SharedAsset;
import dk.northtech.dasscofileproxy.domain.UserAccess;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.List;
import java.util.Random;

@Service
public class SambaServerService {
    private final Jdbi jdbi;

    @Inject
    public SambaServerService(DataSource dataSource) {
        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(ConstructorMapper.factory(SambaServer.class))
                .registerRowMapper(ConstructorMapper.factory(UserAccess.class))
                .registerRowMapper(ConstructorMapper.factory(SharedAsset.class));
    }

    public Long createSambaServer(SambaServer sambaServer) {
        return jdbi.inTransaction(h -> {

                    Long sambaServerId = h.createUpdate("""
                                    insert into "samba_servers"(share_path, container_port, access, creation_datetime)
                                    values(:sharePath, :containerPort, :access::access_type, :creationDatetime)""")
                            .bindMethods(sambaServer)
                            .executeAndReturnGeneratedKeys()
                            .mapTo(Long.class)
                            .one();

                    if (sambaServer.sharedAssets().size() > 0) {
                        SharedAssetList batch = h.attach(SharedAssetList.class);

                        batch.fillBatch(sambaServerId, sambaServer.sharedAssets());
                    }

                    if (sambaServer.userAccess().size() > 0) {
                        UserAccessList batch = h.attach(UserAccessList.class);

                        batch.fillBatch(sambaServerId, sambaServer.userAccess());
                    }

                    return sambaServerId;
                }
        );
    }

    public List<Integer> findAllUsedPorts() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                    select container_port from "samba_servers"
                    """)
                .mapTo(Integer.class)
                .list()
        );
    }

    public String generateRandomToken() {
        int leftLimit = 48; // numeral '0'
        int rightLimit = 122; // letter 'z'
        int targetStringLength = 20;
        Random random = new Random();

        return random.ints(leftLimit, rightLimit + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(targetStringLength)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}

interface SharedAssetList {
    @SqlBatch("INSERT INTO shared_assets(samba_server_id, asset_guid, creation_datetime) VALUES(:newSambaServerId, :assetGuid, :creationDatetime)")
    void fillBatch(@Bind Long newSambaServerId, @BindMethods Collection<SharedAsset> sharedAssets);

    @SqlQuery("SELECT count(1) FROM shared_assets")
    int countBatch();
}

interface UserAccessList {
    @SqlBatch("INSERT INTO user_access(samba_server_id, username, token, creation_datetime) VALUES(:newSambaServerId, :username, :token, :creationDatetime)")
    void fillBatch(@Bind Long newSambaServerId, @BindMethods Collection<UserAccess> userAccess);

    @SqlQuery("SELECT count(1) FROM shared_assets")
    int countBatch();
}
