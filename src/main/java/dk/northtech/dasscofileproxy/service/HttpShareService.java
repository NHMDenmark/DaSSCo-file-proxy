package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.*;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
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

    private final ShareConfig shareConfig;
    private final FileService fileService;
    private final SFTPService sftpService;
    private final AssetService assetService;
    private static final Logger logger = LoggerFactory.getLogger(HttpShareService.class);

    @Inject
    public HttpShareService(DataSource dataSource,FileService fileService, SFTPService sftpService, ShareConfig shareConfig, AssetService assetService) {
        this.fileService = fileService;
        this.sftpService = sftpService;
        this.shareConfig = shareConfig;
        this.assetService = assetService;
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

    public HttpInfo createHttpShareInternal(CreationObj creationObj, User user) {
        try {
            Instant creationDatetime = Instant.now();
            if (!creationObj.users().isEmpty() && !creationObj.assets().isEmpty()) {
                if(creationObj.assets().size() != 1) {
                    throw new IllegalArgumentException("Number of assets must be one");
                }
                MinimalAsset minimalAsset = creationObj.assets().getFirst();
                AssetFull fullAsset = assetService.getFullAsset(minimalAsset.asset_guid());
                if(fullAsset != null && fullAsset.asset_locked) {
                    throw new DasscoIllegalActionException("Asset is locked");
                }
                // Prevents people from checking out random assets as parents
                if(fullAsset != null && fullAsset.parent_guid != null && minimalAsset.parent_guid() != null && !fullAsset.parent_guid.equals(minimalAsset.parent_guid())) {
                    throw new DasscoIllegalActionException("Provided parent is different than the actual parent of the asset");
                }
                FileService.AssetAllocation usageByAsset = fileService.getUsageByAsset(minimalAsset);
                StorageMetrics storageMetrics = getStorageMetrics();
                HttpInfo httpInfo = createHttpInfo(storageMetrics, creationObj, usageByAsset);
                logger.info("creation obj is valid");
                Directory dir = new Directory(null
                        , httpInfo.path()
                        , shareConfig.nodeHost()
                        , AccessType.WRITE
                        , creationDatetime
                        , creationObj.allocation_mb()
                        , false
                        , 0
                        , setupSharedAssets(creationObj.assets()
                            .stream()
                            .map(MinimalAsset::asset_guid)
                            .collect(Collectors.toList()), creationDatetime)
                        , setupUserAccess(creationObj.users(), creationDatetime)
                );
                Directory directory = createDirectory(dir);
                String shareFolder = fileService.createShareFolder(minimalAsset);
                try {
                    if (creationObj.assets().size() == 1) {
                        sftpService.initAssetShare(shareFolder, minimalAsset);
                        return httpInfo;
                    }
                } catch (Exception e) {
                    logger.error("Failed to init asset share", e);
                    fileService.deleteDirectory(directory.directoryId());
                    throw e;
                }
                return new HttpInfo(null, null, storageMetrics.total_storage_mb(), storageMetrics.cache_storage_mb(), storageMetrics.remaining_storage_mb(), storageMetrics.all_allocated_storage_mb(), 0, httpInfo.proxy_allocation_status_text(),httpInfo.http_allocation_status(), 0);
            } else {
                throw new BadRequestException("You have to provide users in this call");
            }
        } catch (RuntimeException e) {
            logger.error("exception", e);
            throw e;
        }
    }

    public HttpInfo createHttpInfo(StorageMetrics storageMetrics, CreationObj creationObj, FileService.AssetAllocation assetAllocation) {
        if(storageMetrics.remaining_storage_mb()-creationObj.allocation_mb() - assetAllocation.getTotalAllocationAsMb()  < 0) {
            return new HttpInfo(null
                    , shareConfig.nodeHost()
                    , storageMetrics.total_storage_mb()
                    , storageMetrics.cache_storage_mb()
                    , storageMetrics.all_allocated_storage_mb()
                    , storageMetrics.remaining_storage_mb()
                    , 0
                    , null
                    , HttpAllocationStatus.DISK_FULL
                    , assetAllocation.getParentAllocationAsMb());
        }
        MinimalAsset minimalAsset = creationObj.assets().getFirst();
        String path = "/assetfiles/" + minimalAsset.institution() + "/" + minimalAsset.collection() +"/" + minimalAsset.asset_guid() +"/";
        return new HttpInfo(path
                , shareConfig.nodeHost()
                , storageMetrics.total_storage_mb()
                , storageMetrics.cache_storage_mb()
                , storageMetrics.all_allocated_storage_mb() + creationObj.allocation_mb()
                , storageMetrics.remaining_storage_mb() - creationObj.allocation_mb()
                , creationObj.allocation_mb()
                , null
                , HttpAllocationStatus.SUCCESS
                , assetAllocation.getParentAllocationAsMb());
    }

    public StorageMetrics getStorageMetrics() {
        return jdbi.withHandle(h -> {
            int totalDiskSpace = shareConfig.totalDiskSpace();//(int) (new File("/").getTotalSpace() / 1000000);
            int cacheDiskSpace = shareConfig.cacheDiskspace();
            int totalAllocated = 0;
            DirectoryRepository attach = h.attach(DirectoryRepository.class);
            totalAllocated = attach.getTotalAllocated();
            return new StorageMetrics(totalDiskSpace, cacheDiskSpace, totalAllocated, totalDiskSpace-totalAllocated-cacheDiskSpace);
        });

    }

    public HttpInfo allocateStorage(AssetStorageAllocation newAllocation) {
        StorageMetrics storageMetrics = getStorageMetrics();
        return jdbi.withHandle(h -> {
            DirectoryRepository directoryRepository = h.attach(DirectoryRepository.class);
            Optional<Directory> writeableDirectory = fileService.getWriteableDirectory(newAllocation.asset_guid());
            if(writeableDirectory.isEmpty()) {
                return new HttpInfo("Failed to allocate storage, no writeable directory found" , HttpAllocationStatus.BAD_REQUEST);
            }
            FileRepository fileRepository = h.attach(FileRepository.class);
            long totalAllocatedAsset = fileRepository.getTotalAllocatedByAsset(newAllocation.asset_guid());
            if(totalAllocatedAsset/1000000 > newAllocation.new_allocation_mb()) {
                return new HttpInfo("Size of files in share is greater than the new value", HttpAllocationStatus.BAD_REQUEST);
            }
            Directory directory = writeableDirectory.get();
            StorageMetrics resultMetrics = storageMetrics.allocate(newAllocation.new_allocation_mb() - directory.allocatedStorageMb());
            if(resultMetrics.remaining_storage_mb() < 0) {
                return new HttpInfo(directory.uri(), directory.node_host(),storageMetrics.total_storage_mb(), storageMetrics.cache_storage_mb(),storageMetrics.all_allocated_storage_mb(), storageMetrics.remaining_storage_mb(), 0, "Cannot allocate more storage", HttpAllocationStatus.DISK_FULL, 0);
            }
            directoryRepository.updateAllocatedStorage(directory.directoryId(), newAllocation.new_allocation_mb());
            return new HttpInfo(directory.uri(), directory.node_host(),resultMetrics.total_storage_mb(), resultMetrics.cache_storage_mb(),resultMetrics.all_allocated_storage_mb(), resultMetrics.remaining_storage_mb(), 0, null, HttpAllocationStatus.SUCCESS, 0);
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

    public HttpInfo deleteShare(User user, String assetGuid) {
        Optional<Directory> dirToDeleteOpt = fileService.getWriteableDirectory(assetGuid);
        StorageMetrics storageMetrics = getStorageMetrics();
        if(dirToDeleteOpt.isEmpty()){
            return new HttpInfo(null
                    , shareConfig.nodeHost()
                    , storageMetrics.total_storage_mb()
                    , storageMetrics.cache_storage_mb()
                    , storageMetrics.all_allocated_storage_mb()
                    , storageMetrics.remaining_storage_mb()
                    , 0
                    , "No share found"
                    , HttpAllocationStatus.SHARE_NOT_FOUND
                    , 0);
        }
        Directory directoryToDelete = dirToDeleteOpt.get();
        return jdbi.withHandle(h -> {
            UserAccessList attach = h.attach(UserAccessList.class);
            List<UserAccess> userAccess = attach.getUserAccess(directoryToDelete.directoryId());
            Optional<UserAccess> first = userAccess.stream().filter(x -> x.username().equals(user.username)).findFirst();
            if(first.isEmpty() && !user.roles.contains(Role.ADMIN.name())){
                logger.warn("User {} tried to delete directory they do not have access to", user.username);
                throw new DasscoIllegalActionException();
            }
            //Clean up database structures
            fileService.deleteDirectory(directoryToDelete.directoryId());
            //Clean up files
            fileService.removeShareFolder(directoryToDelete);
            return new HttpInfo(null
                    , null
                    , shareConfig.totalDiskSpace()
                    , storageMetrics.cache_storage_mb()
                    , storageMetrics.all_allocated_storage_mb()-directoryToDelete.allocatedStorageMb()
                    , storageMetrics.remaining_storage_mb() + directoryToDelete.allocatedStorageMb()
                    ,0
                    ,"Shared deleted"
                    , HttpAllocationStatus.SUCCESS
                    , 0);
        });
    }

    public HttpInfo createHttpShare(CreationObj creationObj, User user) {
        AssetFull fullAsset = assetService.getFullAsset(creationObj.assets().getFirst().asset_guid());
        if(fullAsset.asset_locked) {
            throw new DasscoIllegalActionException("Asset is locked");
        }
        return createHttpShareInternal(creationObj, user);
    }
}



