package dk.northtech.dasscofileproxy.service;

import com.google.common.base.Strings;
import dk.northtech.dasscofileproxy.domain.*;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

@Service
public class SambaServerService {
    private final Jdbi jdbi;
    private final DockerService dockerService;
    private final FileService fileService;
    @Inject
    public SambaServerService(DataSource dataSource, DockerService dockerService, FileService fileService) {
        this.dockerService = dockerService;
        this.fileService = fileService;
        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(ConstructorMapper.factory(SambaServer.class))
                .registerRowMapper(ConstructorMapper.factory(UserAccess.class))
                .registerRowMapper(ConstructorMapper.factory(SharedAsset.class));
    }

    public Long createSambaServer(SambaServer sambaServer) {
        return jdbi.inTransaction(h -> {
                    Long sambaServerId = h.createUpdate("""
                                    insert into "samba_servers"(share_path, host, container_port, access, creation_datetime)
                                    values(:sharePath, :host, :containerPort, :access::access_type, :creationDatetime)""")
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

    public Optional<SambaServer> getSambaServer(String sambaName) {
        long sambaServerId = parseId(sambaName);
        jdbi.withHandle(h -> {
            SambaServerRepository smbRepo = h.attach(SambaServerRepository.class);
            SambaServer sambaServer = smbRepo.getSambaServer(sambaServerId);
            SharedAssetList attach = h.attach(SharedAssetList.class);
            UserAccessList userAccessList = h.attach(UserAccessList.class);
            if(sambaServer != null) {
                return new SambaServer(sambaServer,
                        attach.getSharedAssetsBySambaServer(sambaServerId),
                        userAccessList.getUserAccess(sambaServerId));
            } else {
                return Optional.empty();
            }
        });
        return Optional.empty();
    }

    public long parseId(String sambaName) {
        if (Strings.isNullOrEmpty(sambaName)) {
            throw new IllegalArgumentException("sambaName cannot be null");
        }
        String[] splitName = sambaName.split("_");
        if (splitName.length < 2) {
            throw new RuntimeException("sambaName should follow the format share_<id>");
        }
        try {
            return Long.parseLong(splitName[1]);
        } catch (Exception e) {
            throw new RuntimeException("sambaName should follow the format share_<id>");
        }
    }

    public SambaInfo disconnect(AssetSmbRequest assetSmbRequest, User user) {
        Optional<SambaServer> sambaServerOpt = getSambaServer(assetSmbRequest.shareName());
        if(sambaServerOpt.isPresent()) {
            SambaServer sambaServer = sambaServerOpt.get();
            checkAccess(sambaServer, user);
            if(checkAccess(sambaServer, user)) {
                dockerService.removeContainer(assetSmbRequest.shareName());
            } else {
                throw new DasscoIllegalActionException();
            }
            return new SambaInfo(null, null, "share_" + sambaServer.sambaServerId(), null, SambaRequestStatus.OK_DISCONNECTED, null);
        }
        return new SambaInfo(null, null, null, null, SambaRequestStatus.SMB_FAILED, "Share was not found");
    }

    public boolean close(AssetSmbRequest assetSmbRequest, User user, boolean force, boolean syncERDA) {
        Optional<SambaServer> sambaServerOpt = getSambaServer(assetSmbRequest.shareName());
        if(sambaServerOpt.isPresent()) {
            SambaServer sambaServer = sambaServerOpt.get();
            checkAccess(sambaServer, user);
            if(checkAccess(sambaServer, user)) {
                return dockerService.removeContainer(assetSmbRequest.shareName());
            } else {
                throw new DasscoIllegalActionException();
            }
        } else if (force) {
            dockerService.removeContainer(assetSmbRequest.shareName());
        }
        return false;
    }

    public boolean open(AssetSmbRequest assetSmbRequest, User user, boolean force, boolean syncERDA) {
        Optional<SambaServer> sambaServerOpt = getSambaServer(assetSmbRequest.shareName());
        if(sambaServerOpt.isPresent()) {
            SambaServer sambaServer = sambaServerOpt.get();
            checkAccess(sambaServer, user);
            if(checkAccess(sambaServer, user)) {
                return dockerService.removeContainer(assetSmbRequest.shareName());
            } else {
                throw new DasscoIllegalActionException();
            }
        } else if (force) {
            dockerService.removeContainer(assetSmbRequest.shareName());
        }
        return false;
    }

    public boolean checkAccess(SambaServer sambaServer, User user) {
        for(UserAccess userAccess: sambaServer.userAccess()) {
            if (Objects.equals(user.username, userAccess.username())) {
                return true;
            }
        }
        return false;
    }
}

interface SharedAssetList {
    @SqlBatch("INSERT INTO shared_assets(samba_server_id, asset_guid, creation_datetime) VALUES(:newSambaServerId, :assetGuid, :creationDatetime)")
    void fillBatch(@Bind Long newSambaServerId, @BindMethods Collection<SharedAsset> sharedAssets);

    @SqlQuery("SELECT count(1) FROM shared_assets")
    int countBatch();

    @SqlQuery("SELECT * FROM shared_assets WHERE samba_server_id = :sambaServerId")
    List<SharedAsset> getSharedAssetsBySambaServer(@Bind long sambaServerId);

    @SqlUpdate("DELETE FROM shared_assets WHERE samba_server_id = :samba_server_id")
    void deleteService(@Bind long sambaServerId);
}

interface SambaServerRepository {
    @SqlQuery("SELECT * FROM samba_servers WHERE samba_server_id = :sambaServerId")
    SambaServer getSambaServer(@Bind long sambaServerId);

    @SqlUpdate("DELETE FROM samba_servers WHERE samba_server_id = :samba_server_id")
    void deleteService(@Bind long sambaServerId);
}

interface UserAccessList {
    @SqlBatch("INSERT INTO user_access(samba_server_id, username, token, creation_datetime) VALUES (:newSambaServerId, :username, :token, :creationDatetime)")
    void fillBatch(@Bind Long newSambaServerId, @BindMethods Collection<UserAccess> userAccess);

    @SqlQuery("SELECT count(1) FROM shared_assets")
    int countBatch();

    @SqlQuery("SELECT * FROM user_access WHERE samba_server_id = :sambaServerId")
    List<UserAccess> getUserAccess(@Bind long sambaServerId);

    @SqlUpdate("DELETE FROM user_access WHERE samba_server_id = :samba_server_id")
    void deleteService(@Bind long sambaServerId);
}
