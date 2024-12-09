package dk.northtech.dasscofileproxy.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Strings;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoIllegalActionException;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoInternalErrorException;
import dk.northtech.dasscofileproxy.repository.*;
import dk.northtech.dasscofileproxy.webapi.model.AssetStorageAllocation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;

@Service
public class HttpShareService {
    Cache<String, Instant> guids = Caffeine.newBuilder() // <user, <"read", ["collection2"]>>
            .expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Jdbi jdbi;
    private final ShareConfig shareConfig;
    private final FileService fileService;
    private final SFTPService sftpService;
    private final AssetService assetService;
    private static final Logger logger = LoggerFactory.getLogger(HttpShareService.class);
    private final ObservationRegistry observationRegistry;

    @Inject
    public HttpShareService(DataSource dataSource, FileService fileService, SFTPService sftpService,
                            ShareConfig shareConfig, AssetService assetService, ObservationRegistry observationRegistry) {
        this.fileService = fileService;
        this.sftpService = sftpService;
        this.shareConfig = shareConfig;
        this.assetService = assetService;
        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(ConstructorMapper.factory(Directory.class))
                .registerRowMapper(ConstructorMapper.factory(UserAccess.class))
                .registerRowMapper(ConstructorMapper.factory(SharedAsset.class));
        this.observationRegistry = observationRegistry;
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

    public synchronized void checkCreationObject(CreationObj creationObj) {
        if (creationObj.users().isEmpty() || creationObj.assets().isEmpty()) {
            throw new BadRequestException("You have to provide users and an asset in this call");
        }
        if (creationObj.assets().size() != 1) {
            logger.warn("Create writeable share api received number of assets different than one");
            throw new IllegalArgumentException("Number of assets must be one");
        }
        String guid = creationObj.assets().getFirst().asset_guid();
        if (Strings.isNullOrEmpty(guid)) {
            throw new IllegalArgumentException("Asset guid cannot be null or empty");
        }
        Instant time = guids.getIfPresent(guid);
        if(time != null) {
            throw new IllegalArgumentException("A share with guid "+guid+" is already in the process of creation");
        }
        guids.put(guid, Instant.now());
    }

    public HttpInfo createHttpShareInternal(CreationObj creationObj, User user) {
        try {
            Instant creationDatetime = Instant.now();

            checkCreationObject(creationObj);
            MinimalAsset minimalAsset = creationObj.assets().getFirst();
            Optional<Directory> writeableDirectory = fileService.getWriteableDirectory(minimalAsset.asset_guid());
            if (writeableDirectory.isPresent()) {
                throw new DasscoIllegalActionException("Asset is already checked out");
            }
            LocalDateTime getFullAssetStart = LocalDateTime.now();
            logger.info("#4.1: Making API Call to AssetService to get full asset:");
            AssetFull fullAsset = assetService.getFullAsset(minimalAsset.asset_guid());
            LocalDateTime getFullAssetEnd = LocalDateTime.now();
            logger.info("#4.1 took {} ms", java.time.Duration.between(getFullAssetStart, getFullAssetEnd).toMillis());
            if (fullAsset != null && fullAsset.asset_locked) {
                logger.warn("Cannot create writeable share: Asset {} is locked", fullAsset.asset_guid);
                guids.invalidate(minimalAsset.asset_guid());
                throw new DasscoIllegalActionException("Asset is locked");
            }
            // Prevents people from checking out random assets as parents
            if (fullAsset != null && fullAsset.parent_guid != null && minimalAsset.parent_guid() != null && !fullAsset.parent_guid.equals(minimalAsset.parent_guid())) {
                logger.warn("{} is not the parent of {}", minimalAsset.parent_guid(), minimalAsset.asset_guid());
                guids.invalidate(fullAsset.asset_guid);
                throw new DasscoIllegalActionException("Provided parent is different than the actual parent of the asset");
            }
            LocalDateTime getUsageByAssetStart = LocalDateTime.now();
            FileService.AssetAllocation usageByAsset = fileService.getUsageByAsset(minimalAsset);
            LocalDateTime getUsageByAssetEnd = LocalDateTime.now();
            logger.info("#4.2 Getting usage took {} ms", java.time.Duration.between(getUsageByAssetStart, getUsageByAssetEnd).toMillis());
            LocalDateTime getStorageMetricsStart = LocalDateTime.now();
            StorageMetrics storageMetrics = getStorageMetrics();
            LocalDateTime getStorageMetricsEnd = LocalDateTime.now();
            logger.info("#4.3 Getting the storage metrics took {} ms", java.time.Duration.between(getStorageMetricsStart, getStorageMetricsEnd).toMillis());
            logger.info("Storage metrics {}", storageMetrics);
            HttpInfo httpInfo = createHttpInfo(storageMetrics, creationObj, usageByAsset);
            if (httpInfo.http_allocation_status() != HttpAllocationStatus.SUCCESS) {
                guids.invalidate(fullAsset.asset_guid);
                return httpInfo;
            }
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
            // d
            Directory directory = createDirectory(dir);
            Optional<String> shareFolderOpt = fileService.createShareFolder(minimalAsset);
            if (shareFolderOpt.isEmpty()) {
                throw new DasscoInternalErrorException("Local asset directory did not get created, this might be due to a disk space or permission error on the server.");
            }
            String shareFolder = shareFolderOpt.get();
            try {
                if (creationObj.assets().size() == 1) {
                    LocalDateTime initAssetShareStart = LocalDateTime.now();
                    sftpService.initAssetShare(shareFolder, minimalAsset);
                    LocalDateTime initAssetShareEnd = LocalDateTime.now();
                    logger.info("#4.4: Initializing Asset Share took {} ms", java.time.Duration.between(initAssetShareStart, initAssetShareEnd).toMillis());
                    guids.invalidate(minimalAsset.asset_guid());
                    return httpInfo;
                }
            } catch (Exception e) {
                logger.error("Failed to init asset share", e);
                fileService.deleteDirectory(directory.directoryId());
                throw e;
            }
            return new HttpInfo(null, null, storageMetrics.total_storage_mb(), storageMetrics.cache_storage_mb(), storageMetrics.remaining_storage_mb(), storageMetrics.all_allocated_storage_mb(), 0, httpInfo.allocation_status_text(), httpInfo.http_allocation_status(), 0);

        } catch (RuntimeException e) {
            logger.error("exception", e);
            throw e;
        }
    }

    public HttpInfo createHttpInfo(StorageMetrics storageMetrics, CreationObj creationObj, FileService.AssetAllocation assetAllocation) {
        if (assetAllocation.getTotalAllocationAsMb() > creationObj.allocation_mb()) {
            return new HttpInfo(null
                    , shareConfig.nodeHost()
                    , storageMetrics.total_storage_mb()
                    , storageMetrics.cache_storage_mb()
                    , storageMetrics.all_allocated_storage_mb()
                    , storageMetrics.remaining_storage_mb()
                    , 0
                    , "Total size of existing asset files (and parent folder), exceeds requested allocation"
                    , HttpAllocationStatus.BAD_REQUEST
                    , assetAllocation.getParentAllocationAsMb());
        }
        if (storageMetrics.remaining_storage_mb() - creationObj.allocation_mb() - assetAllocation.getTotalAllocationAsMb() < 0) {
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
        String path = "/assetfiles/" + minimalAsset.institution() + "/" + minimalAsset.collection() + "/" + minimalAsset.asset_guid() + "/";
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
        return Observation.createNotStarted("persist:getStorageMetrics", observationRegistry).observe(() -> {
            return jdbi.withHandle(h -> {
                File file = new File(shareConfig.mountFolder());
                long totalSpace = file.getTotalSpace();
                long usableSpace = file.getUsableSpace();
                long freeSpace = file.getFreeSpace();
                logger.info("totalSpace {}", totalSpace);
                logger.info("Free space {}", freeSpace);
                logger.info("Usable space {}", usableSpace);
                int totalAllocated = 0;
                long foldersize = fileService.getFoldersize(shareConfig.mountFolder());
                logger.info("Size of folder {}", foldersize);
                DirectoryRepository attach = h.attach(DirectoryRepository.class);
                totalAllocated = attach.getTotalAllocated();
                int totalDiskSpace = (int) (totalSpace / 1000000L);
                int cacheDiskSpace = shareConfig.cacheDiskspace();
                long totalAllocatedB = totalAllocated * 1000000L;
                logger.info("tolal allocated {}", totalAllocatedB);
                // We have to calculate the remaining disk space including allocations that have not been fully used
                // long actualUsable = usableSpace - foldersize;
                long actualRemaining = usableSpace - (totalAllocatedB - foldersize);
                return new StorageMetrics(totalDiskSpace, cacheDiskSpace, totalAllocated, (int) (actualRemaining / 1000000));
            });
        });
    }

    public HttpInfo allocateStorage(AssetStorageAllocation newAllocation) {
        StorageMetrics storageMetrics = getStorageMetrics();
        return jdbi.withHandle(h -> {
            DirectoryRepository directoryRepository = h.attach(DirectoryRepository.class);
            Optional<Directory> writeableDirectory = fileService.getWriteableDirectory(newAllocation.asset_guid());
            if (writeableDirectory.isEmpty()) {
                return new HttpInfo("Failed to allocate storage, no writeable directory found", HttpAllocationStatus.BAD_REQUEST);
            }
            Directory directory = writeableDirectory.get();
            File localDirectory = new File(shareConfig.mountFolder() + directory.uri() + "/parent");
            long parentSize = 0L;
            FileRepository fileRepository = h.attach(FileRepository.class);
            if (localDirectory.exists()) {
                AssetFull fullAsset = assetService.getFullAsset(newAllocation.asset_guid());
                if (fullAsset.parent_guid != null) {
                    parentSize = fileRepository.getTotalAllocatedByAsset(fullAsset.parent_guid);
                }
            }
            long totalAllocatedAsset = parentSize + fileRepository.getTotalAllocatedByAsset(newAllocation.asset_guid());
            if (totalAllocatedAsset / 1000000 > newAllocation.new_allocation_mb()) {
                return new HttpInfo("Size of files in share is greater than the new value", HttpAllocationStatus.BAD_REQUEST);
            }
            StorageMetrics resultMetrics = storageMetrics.allocate(newAllocation.new_allocation_mb() - directory.allocatedStorageMb());
            if (resultMetrics.remaining_storage_mb() < 0) {
                return new HttpInfo(directory.uri(), directory.node_host(), storageMetrics.total_storage_mb(), storageMetrics.cache_storage_mb(), storageMetrics.all_allocated_storage_mb(), storageMetrics.remaining_storage_mb(), 0, "Cannot allocate more storage", HttpAllocationStatus.DISK_FULL, 0);
            }
            directoryRepository.updateAllocatedStorage(directory.directoryId(), newAllocation.new_allocation_mb());
            return new HttpInfo(directory.uri(), directory.node_host(), resultMetrics.total_storage_mb(), resultMetrics.cache_storage_mb(), resultMetrics.all_allocated_storage_mb(), resultMetrics.remaining_storage_mb(), newAllocation.new_allocation_mb(), null, HttpAllocationStatus.SUCCESS, parentSize / 1000000);
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
        if (dirToDeleteOpt.isEmpty()) {
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
        // Do not allow share that is synchronizing to be deleted as it is being used by another process. Permit cleaning failed shares.
        if (directoryToDelete.awaitingErdaSync() && directoryToDelete.erdaSyncAttempts() < shareConfig.maxErdaSyncAttempts()) {
            logger.warn("Attempt to delete share that is synchronizing");
            return new HttpInfo(null
                    , shareConfig.nodeHost()
                    , storageMetrics.total_storage_mb()
                    , storageMetrics.cache_storage_mb()
                    , storageMetrics.all_allocated_storage_mb()
                    , storageMetrics.remaining_storage_mb()
                    , 0
                    , "Share is synchronizing"
                    , HttpAllocationStatus.BAD_REQUEST
                    , 0);
        }
        return jdbi.withHandle(h -> {
            UserAccessList attach = h.attach(UserAccessList.class);
            List<UserAccess> userAccess = attach.getUserAccess(directoryToDelete.directoryId());
            Optional<UserAccess> first = userAccess.stream()
                    .filter(x -> x.username().equals(user.username)).findFirst();
            if (first.isEmpty() && !user.roles.contains(Role.ADMIN.roleName)) {
                logger.warn("User {} tried to delete directory they do not have access to", user.username);
                throw new DasscoIllegalActionException();
            }
            //Clean up database structures
            fileService.resetDirectoryAndResetFiles(directoryToDelete.directoryId(), assetGuid);
            //Clean up files
            fileService.removeShareFolder(directoryToDelete);
            return new HttpInfo(null
                    , null
                    , shareConfig.totalDiskSpace()
                    , storageMetrics.cache_storage_mb()
                    , storageMetrics.all_allocated_storage_mb() - directoryToDelete.allocatedStorageMb()
                    , storageMetrics.remaining_storage_mb() + directoryToDelete.allocatedStorageMb()
                    , 0
                    , "Share deleted"
                    , HttpAllocationStatus.SUCCESS
                    , 0);
        });
    }


    public HttpInfo createHttpShare(CreationObj creationObj, User user) {
        MinimalAsset asset = creationObj.assets().getFirst();
        AssetFull fullAsset = assetService.getFullAsset(asset.asset_guid());
        if (fullAsset == null) {
            throw new DasscoIllegalActionException("Asset [" + asset.asset_guid() + "] was not found");
        }
        if (fullAsset.asset_locked) {
            throw new DasscoIllegalActionException("Asset is locked");
        }
        CreationObj mappedCreationObject = mapCreationObject(creationObj, asset, fullAsset);
        return createHttpShareInternal(mappedCreationObject, user);
    }


    static CreationObj mapCreationObject(CreationObj creationObj, MinimalAsset asset, AssetFull fullAsset) {
        if (asset.institution() != null && !(asset.institution().equals(fullAsset.institution))) {
            throw new DasscoIllegalActionException("Institution in creation object should match institution of asset with supplied guid or be set to null");
        }
        if (asset.collection() != null && !(asset.collection().equals(fullAsset.collection))) {
            throw new DasscoIllegalActionException("Collection in creation object should match collection of asset with supplied guid or be set to null");
        }
        MinimalAsset minimalAsset = new MinimalAsset(asset.asset_guid(), asset.parent_guid(), fullAsset.institution, fullAsset.collection);
        CreationObj mappedCreationObject = new CreationObj(List.of(minimalAsset), creationObj.users(), creationObj.allocation_mb());
        return mappedCreationObject;
    }

    public List<Share> listShares() {
        return jdbi.withHandle(h -> {
            SharedAssetList sharedAssetList = h.attach(SharedAssetList.class);
            DirectoryRepository directoryRepository = h.attach(DirectoryRepository.class);
            List<SharedAsset> sharedAssets = sharedAssetList.getSharedAssets();
            Map<Long, Share> shares = directoryRepository.getAll().stream()
                    .map(x -> new Share(x.uri(), x.directoryId(), new ArrayList<>()))
                    .collect(Collectors.toMap(x -> x.id, y -> {
                        return y;
                    }));
            for (SharedAsset asset : sharedAssets) {
                shares.get(asset.directoryId()).assets.add(asset.assetGuid());
            }
            return new ArrayList<>(shares.values());
        });
    }

    public record Share(String path, long id, List<String> assets) {
    }
}



