package dk.northtech.dasscofileproxy.service;

//import com.jcraft.jsch.*;

import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.DirectoryRepository;
import dk.northtech.dasscofileproxy.repository.SharedAssetRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class SFTPService {
    private final SFTPConfig sftpConfig;
    private final Jdbi jdbi;

    private final FileService fileService;
    private final ShareConfig shareConfig;
    private final AssetService assetService;

    private ErdaDataSource erdaDataSource;
    //    private HttpShareService httpShareService;
    private static final Logger logger = LoggerFactory.getLogger(SFTPService.class);
    private final ObservationRegistry observationRegistry;

    @Inject
    public SFTPService(SFTPConfig sftpConfig, FileService fileService, ShareConfig shareConfig, AssetService assetService,
                       Jdbi jdbi, ErdaDataSource erdaDataSource, ObservationRegistry observationRegistry) {
        this.sftpConfig = sftpConfig;
        this.assetService = assetService;
        this.fileService = fileService;
        this.shareConfig = shareConfig;
        this.jdbi = jdbi;
        this.erdaDataSource = erdaDataSource;
        this.observationRegistry = observationRegistry;
    }


    public void moveToERDA(AssetUpdate assetUpdate) {
        Optional<Directory> writeableDirectory = fileService.getWriteableDirectory(assetUpdate.assetGuid());
        if (writeableDirectory.isPresent()) {
            fileService.scheduleDirectoryForSynchronization(writeableDirectory.get().directoryId(), assetUpdate);
        } else {
            throw new IllegalArgumentException("No writeable share for asset found");
        }
    }

    public List<Directory> getHttpSharesToSynchronize(int maxAttempts) {
        return jdbi.withHandle(h -> {
            DirectoryRepository attach = h.attach(DirectoryRepository.class);
            return attach.getDirectoriesForSynchronization(maxAttempts);

        });
    }

    public List<SharedAsset> getShardAsset(long directoryId) {
        return jdbi.withHandle(h -> {
            SharedAssetRepository attach = h.attach(SharedAssetRepository.class);
            return attach.getSharedAssetsByDirectory(directoryId);
        });
    }

    record FailedAsset(String guid, String errorMessage) {
    }

    @Scheduled(cron = "0 * * * * *")
    public void moveFiles() {
        List<Directory> directories = getHttpSharesToSynchronize(shareConfig.maxErdaSyncAttempts());
        List<FailedAsset> failedGuids = new ArrayList<>();

        // If we do not get an ERDA connection immediately all connections are likely being used. Prioritise requests from REST api.
        try(ERDAClient erdaClient = erdaDataSource.acquire(1)) {
            for (Directory directory : directories) {
                List<SharedAsset> sharedAssetList = getShardAsset(directory.directoryId());
                if (sharedAssetList.size() != 1) {
                    throw new RuntimeException("Directory has multiple shared assets");
                }
                SharedAsset sharedAsset = sharedAssetList.get(0);

                try {
                    AssetFull fullAsset = assetService.getFullAsset(sharedAsset.assetGuid());
                    if (fullAsset.asset_locked) {
                        logger.info("Asset {} is locked", sharedAsset.assetGuid());
                        failedGuids.add(new FailedAsset(fullAsset.asset_guid, "Asset is locked"));
                    }
                    String remotePath = getRemotePath(new MinimalAsset(fullAsset.asset_guid, fullAsset.parent_guids, fullAsset.institution, fullAsset.collection));
                    String localMountFolder = this.shareConfig.mountFolder() + directory.uri();
                    File localDirectory = new File(shareConfig.mountFolder() + directory.uri());
                    List<File> files = fileService.listFiles(localDirectory, new ArrayList<>(), false, false);
                    List<Path> remoteLocations = files.stream().map(file -> {
                        logger.info("Remote path is: " + remotePath);
                        logger.info("Local base path is: " + localMountFolder);
                        logger.info("File path is: " + file.toPath().toString().replace("\\", "/"));
                        logger.info(file.toPath().toString().replace("\\", "/").split(localMountFolder)[1]);
//                        logger.info(file.toPath().toString().replace("\\", "/").split(localMountFolder)[1]);
                        String[] split = file.toPath().toString().replace("\\", "/").split(localMountFolder);
                        return Path.of(remotePath + "/" + (split.length == 2 ? split[1]:split[0]));
//                        return Path.of(remotePath + "/" + file.toPath().toString().replace("\\", "/").replace(localMountFolder, ""));
                    }).collect(Collectors.toList());
                    erdaClient.createSubDirsIfNotExists(remoteLocations);
                    List<String> remoteFiles = erdaClient.listAllFiles(remotePath);
//            }
                    final Set<String> uploadedFiles = erdaClient.putFilesOnRemotePathBulk(files, localMountFolder, remotePath);
                    //handle files that have been deleted
                    List<String> filesToDelete = remoteFiles.stream().filter(f -> !uploadedFiles.contains(f)).collect(Collectors.toList());
                    erdaClient.deleteFiles(filesToDelete);
                    fileService.markFilesAsSynced(fullAsset.asset_guid);
                    if (assetService.completeAsset(new AssetUpdateRequest(new MinimalAsset(sharedAsset.assetGuid(), null, null, null), directory.syncWorkstation(), directory.syncPipeline(), directory.syncUser()))) {
                        //Clean up local dir and its metadata
                        fileService.deleteDirectory(directory.directoryId());
                        fileService.removeShareFolder(directory);
                        //Clean up files
                        fileService.deleteFilesMarkedAsDeleteByAsset(sharedAsset.assetGuid());
                    }
                } catch (Exception e) {
                    logger.warn("ERDA export failed, failed asset guid: {}", sharedAsset.assetGuid());
                    logger.warn("ERDA sync attempts for failed asset {}", directory.erdaSyncAttempts());
                    logger.info("ERDA sync max attempts {}", shareConfig.maxErdaSyncAttempts());

                    if (directory.erdaSyncAttempts() == shareConfig.maxErdaSyncAttempts()) {
                        logger.info("Asset failed");
                        failedGuids.add(new FailedAsset(sharedAsset.assetGuid(), e.getMessage()));
                    }
                    logger.error("Error doing ERDA synchronisation", e);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        for (FailedAsset s : failedGuids) {
            logger.error("ERDA sync failed for asset {}, retry attemps exhausted", s.guid);
            assetService.setAssestStatus(s.guid(), InternalStatus.ERDA_FAILED, s.errorMessage);
        }
    }

    public String getRemotePath(String institution, String collection, String assetGuid) {
        return sftpConfig.remoteFolder() + institution + "/" + collection + "/" + assetGuid;
    }

    public String getRemotePath(MinimalAsset asset) {
        return sftpConfig.remoteFolder() + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/";
    }

    public List<String> getRemotePathElements(AssetFull asset) {
        return Arrays.asList(sftpConfig.remoteFolder(), asset.institution, asset.collection, asset.asset_guid);
    }

    public String getLocalFolder(String institution, String collection, String assetGuid) {
        return sftpConfig.localFolder() + institution + "/" + collection + "/" + assetGuid;
    }

    public void initAssetShare(String sharePath, MinimalAsset minimalAsset) {
        Observation.createNotStarted("persist:init-asset-share", observationRegistry).observe(() -> {
            //        AssetFull asset = assetService.getFullAsset(assetGuid);
            String remotePath = getRemotePath(minimalAsset);
            logger.info("Initialising asset folder, remote path is {}", remotePath);

            try (ERDAClient erdaClient = erdaDataSource.acquire(120);) {
                if (!erdaClient.exists(remotePath, true)) {
                    logger.info("Remote path {} didnt exist ", remotePath);
                } else {
                    List<String> fileNames = erdaClient.listAllFiles(remotePath);
                    erdaClient.downloadFiles(fileNames, sharePath, minimalAsset.asset_guid());
                }
                try {
                    logger.info("Initialising parent folder, parent_guid is {}", minimalAsset.parent_guids());

                    //If asset have parent download into parent folder
                    //We could save a http request here as we dont need the full parent asset to get the remote location, it is in the same collection and institution.
                        minimalAsset.parent_guids().forEach(parent_guid -> {

                        AssetFull parent = assetService.getFullAsset(parent_guid);
                        if(parent == null) {
                            throw new IllegalArgumentException("parent doesnt exist");
                        }
                        String parentRemotePath = getRemotePath(new MinimalAsset(parent.asset_guid, parent.parent_guids, parent.institution, parent.collection));
                        logger.info("Initialising parent folder, remote path is {}", parentRemotePath);
                        try {
                            if (!erdaClient.exists(parentRemotePath, true)) {
                                logger.info("Remote parent path {} didnt exist ", parentRemotePath);
                                throw new RuntimeException("Remote path doesnt exist");
//                         return;
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        List<String> parentFileNames = erdaClient.listAllFiles(parentRemotePath);
                        erdaClient.downloadFiles(parentFileNames, sharePath + "/parents/" + parent.asset_guid, parent.asset_guid);
                        });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

    }


    public void cacheFile(String remotePath, String localPath) {
        try (ERDAClient erdaClient = new ERDAClient(sftpConfig)) {

            String parentPath = localPath.substring(0, localPath.lastIndexOf('/'));

            Files.createDirectories(Path.of(parentPath));

            erdaClient.downloadFile(remotePath, localPath);

            long savedFileSize = Files.size(Path.of(localPath));

            // Determine expiration datetime based on file size
            AssetCache assetCache;
            var now = LocalDateTime.now();
            if (savedFileSize < 10 * 1000000) {
                assetCache = new AssetCache(0L, localPath, savedFileSize, now.plusMonths(3), now);
            } else if (savedFileSize < 50 * 1000000) {
                assetCache = new AssetCache(0L, localPath, savedFileSize, now.plusWeeks(1), now);
            } else {
                assetCache = new AssetCache(0L, localPath, savedFileSize, now.plusDays(1), now);
            }

            // Insert the AssetCache into the database
            this.jdbi.inTransaction(h -> h
                    .createUpdate("""
                            INSERT INTO asset_caches (asset_path, file_size, expiration_datetime, creation_datetime)
                            VALUES (:assetPath, :fileSize, :expirationDatetime, :creationDatetime)
                            """)
                    .bindMethods(assetCache)
                    .executeAndReturnGeneratedKeys()
                    .mapTo(AssetCache.class)
                    .findOne()
            );
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
