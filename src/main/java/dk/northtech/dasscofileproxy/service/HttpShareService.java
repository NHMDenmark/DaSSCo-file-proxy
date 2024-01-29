package dk.northtech.dasscofileproxy.service;

import com.google.common.base.Strings;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.DirectoryRepository;
import dk.northtech.dasscofileproxy.repository.SambaServerRepository;
import dk.northtech.dasscofileproxy.repository.SharedAssetList;
import dk.northtech.dasscofileproxy.repository.UserAccessList;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class HttpShareService {
    private final Jdbi jdbi;
    private final DockerService dockerService;
    private final ShareConfig shareConfig;
    private final FileService fileService;
    private final SFTPService sftpService;
    private static final Logger logger = LoggerFactory.getLogger(HttpShareService.class);

    @Inject
    public HttpShareService(DataSource dataSource, DockerService dockerService, FileService fileService, SFTPService sftpService, ShareConfig shareConfig) {
        this.dockerService = dockerService;
        this.fileService = fileService;
        this.sftpService = sftpService;
        this.shareConfig = shareConfig;
        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(ConstructorMapper.factory(Directory.class))
                .registerRowMapper(ConstructorMapper.factory(UserAccess.class))
                .registerRowMapper(ConstructorMapper.factory(SharedAsset.class));
    }

    public Directory createDirectory(Directory directory) {
        return jdbi.inTransaction(h -> {
                    DirectoryRepository di = h.attach(DirectoryRepository.class);

                    Long directoryId = di.insertDirectory(directory);

                    if (directory.sharedAssets().size() > 0) {
                        SharedAssetList sharedAssetRepository = h.attach(SharedAssetList.class);
                        sharedAssetRepository.fillBatch(directoryId, directory.sharedAssets());
                    }

                    if (directory.userAccess().size() > 0) {
                        UserAccessList userAccessRepository = h.attach(UserAccessList.class);
                        userAccessRepository.fillBatch(directoryId, directory.userAccess());
                    }

                    return new Directory(directory, directoryId);
                }
        );
    }

    public HttpInfo createHttpShare(CreationObj creationObj, User user) {
        try {
            Instant creationDatetime = Instant.now();
            if (creationObj.users().size() > 0 && creationObj.assets().size() > 0) {
                StorageMetrics storageMetrics = getStorageMetrics();
                HttpInfo httpInfo = createHttpInfo(storageMetrics, creationObj);
                logger.info("creation obj is valid");
                Directory dir = new Directory(null
                        , shareConfig.mountFolder()
                        , AccessType.WRITE //TODO make it possible to set type here
                        , creationDatetime
                        , creationObj.allocation_mb()
                        , setupSharedAssets(creationObj.assets()
                        .stream()
                        .map(asset -> asset.asset_guid())
                        .collect(Collectors.toList()), creationDatetime), setupUserAccess(creationObj.users(), creationDatetime)
                );
                Directory directory = createDirectory(dir);
                String shareFolder = fileService.createShareFolder(directory.directoryId());
                try {
                    if (creationObj.assets().size() == 1) {
                        sftpService.initAssetShare(shareFolder, creationObj.assets().get(0).asset_guid());
                        return httpInfo;
                    }
                } catch (Exception e) {
                    logger.error("Failed to init asset share", e);
                    deleteDirectory(directory.directoryId());
                    throw e;
                }
//                dockerService.startService(sambaServer);

                return new HttpInfo(null, null, storageMetrics.total_storage_mb(), storageMetrics.cache_storage_mb(), storageMetrics.remaining_storage_mb(), 0, httpInfo.proxy_allocation_status_text(),httpInfo.httpAllocationStatus());
//            return new SambaConnection("127.0.0.2", sambaServer.containerPort(), "share_" + sambaServer.sambaServerId(), sambaServer.userAccess().get(0).token());
            } else {
                throw new BadRequestException("You have to provide users in this call");
            }
        } catch (RuntimeException e) {
            logger.error("exception", e);
            throw e;
        }
    }

    public HttpInfo createHttpInfo(StorageMetrics storageMetrics, CreationObj creationObj) {
        if(storageMetrics.remaining_storage_mb()-creationObj.allocation_mb() < 0) {
            return new HttpInfo(null
                    , shareConfig.nodeHost()
                    , storageMetrics.total_storage_mb()
                    , storageMetrics.cache_storage_mb()
                    , storageMetrics.remaining_storage_mb()
                    , 0
                    , null
                    , HttpAllocationStatus.DISK_FULL);
        }
        MinimalAsset minimalAsset = creationObj.assets().get(0);
        String path = "/assets/" + minimalAsset.institution() + "/" + minimalAsset.collection() +"/";
        return new HttpInfo(path
                , shareConfig.nodeHost()
                , storageMetrics.total_storage_mb()
                , storageMetrics.cache_storage_mb()
                , storageMetrics.remaining_storage_mb() - creationObj.allocation_mb()
                , creationObj.allocation_mb()
                , null
                , HttpAllocationStatus.SUCCESS);
    }

    public StorageMetrics getStorageMetrics() {
        return jdbi.withHandle(h -> {
            int totalDiskSpace = shareConfig.totalDiskpace();
            int cacheDiskSpace = shareConfig.cacheDiskspace();
            int totalAllocated = 0;
            DirectoryRepository attach = h.attach(DirectoryRepository.class);
            totalAllocated = attach.getTotalAllocated();
            return new StorageMetrics(totalDiskSpace, cacheDiskSpace, totalAllocated, totalDiskSpace-totalAllocated);
        });

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
        return jdbi.withHandle(h -> {
            SambaServerRepository smbRepo = h.attach(SambaServerRepository.class);
            SambaServer sambaServer = smbRepo.getSambaServer(sambaServerId);
            SharedAssetList attach = h.attach(SharedAssetList.class);
            UserAccessList userAccessList = h.attach(UserAccessList.class);
            if (sambaServer != null) {
                return Optional.of(
                        new SambaServer(sambaServer
                                , attach.getSharedAssetsByDirectory(sambaServerId)
                                , userAccessList.getUserAccess(sambaServerId)));
            } else {
                return Optional.empty();
            }
        });
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
        if (sambaServerOpt.isPresent()) {
            SambaServer sambaServer = sambaServerOpt.get();
            checkAccess(sambaServer, user);
            if (checkAccess(sambaServer, user)) {
                dockerService.removeContainer(assetSmbRequest.shareName());

            } else {
                throw new DasscoIllegalActionException();
            }
            return new SambaInfo(null, null, "share_" + sambaServer.sambaServerId(), null, SambaRequestStatus.OK_DISCONNECTED, null);
        }
        return new SambaInfo(null, null, null, null, SambaRequestStatus.SMB_FAILED, "Share was not found");
    }

    public boolean close(AssetUpdateRequest assetSmbRequest, User user, boolean force, boolean syncERDA) {
        Optional<SambaServer> sambaServerOpt = getSambaServer(assetSmbRequest.shareName());
        if (sambaServerOpt.isPresent()) {
            SambaServer sambaServer = sambaServerOpt.get();
//            checkAccess(sambaServer, user);
            if (checkAccess(sambaServer, user)) {
                if (syncERDA) {
                    dockerService.removeContainer(assetSmbRequest.shareName());
                    sftpService.moveToERDA(new SambaToMove(sambaServer, assetSmbRequest));
                } else {
                    deleteDirectory(sambaServer.sambaServerId());
                    return dockerService.removeContainer(assetSmbRequest.shareName());
                }
                return false;
            } else {
                throw new DasscoIllegalActionException();
            }
        } else if (force) {
            dockerService.removeContainer(assetSmbRequest.shareName());
        }
        return false;
    }


    public SambaServer open(AssetSmbRequest assetSmbRequest, User user) {
        Optional<SambaServer> sambaServerOpt = getSambaServer(assetSmbRequest.shareName());
        if (sambaServerOpt.isPresent()) {
            SambaServer sambaServer = sambaServerOpt.get();
            checkAccess(sambaServer, user);
            if (checkAccess(sambaServer, user)) {
                dockerService.startService(sambaServer);
                jdbi.withHandle(h -> {
                    SambaServerRepository attach = h.attach(SambaServerRepository.class);
                    attach.updateShared(true, sambaServer.sambaServerId());
                    return h;
                });
                return new SambaServer(sambaServer.sambaServerId(), sambaServer.sharePath(), true, sambaServer.containerPort(), sambaServer.access(), sambaServer.creationDatetime(), sambaServer.sharedAssets(), sambaServer.userAccess());
            } else {
                throw new DasscoIllegalActionException();
            }
        } else {
            throw new IllegalArgumentException("Samba server doesnt exist");
        }
    }

    public boolean checkAccess(SambaServer sambaServer, User user) {
        for (UserAccess userAccess : sambaServer.userAccess()) {
            if (Objects.equals(user.username, userAccess.username())) {
                return true;
            }
        }
        return false;
    }

    public void deleteDirectory(long directoryId) {
        jdbi.inTransaction(h -> {
            SharedAssetList sharedAssetRepository = h.attach(SharedAssetList.class);
            UserAccessList userAccessRepository = h.attach(UserAccessList.class);
            DirectoryRepository directoryRepository = h.attach(DirectoryRepository.class);
            userAccessRepository.deleteUserAccess(directoryId);
            sharedAssetRepository.deleteSharedAsset(directoryId);
            directoryRepository.deleteSharedAsset(directoryId);
            return h;
        });
    }

    public List<UserAccess> setupUserAccess(List<String> users, Instant creationDateTime) {
        ArrayList<UserAccess> userAccess = new ArrayList<>();

        users.forEach(username -> {
            userAccess.add(new UserAccess(null, null, username, generateRandomToken(), creationDateTime));
        });

        return userAccess;
    }

    public List<SharedAsset> setupSharedAssets(List<String> assetGuids, Instant creationDateTime) {
        ArrayList<SharedAsset> sharedAssets = new ArrayList<>();

        assetGuids.forEach(assetGuid -> {
            sharedAssets.add(new SharedAsset(null, null, assetGuid, creationDateTime));
        });

        return sharedAssets;
    }
}



