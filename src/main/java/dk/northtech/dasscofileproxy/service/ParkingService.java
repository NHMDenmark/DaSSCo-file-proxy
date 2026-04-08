package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import org.apache.http.protocol.HttpService;
import org.apache.tika.Tika;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dk.northtech.dasscofileproxy.domain.HttpAllocationStatus.SUCCESS;
import static dk.northtech.dasscofileproxy.service.FileService.writeToDiskAndGetCRC;

@Service
public class ParkingService {
    private static final Logger logger = LoggerFactory.getLogger(ParkingService.class.getName());
    private final Jdbi jdbi;
    private final HttpShareService httpShareService;
    private final FileService fileService;
    private final ShareConfig shareConfig;

    public ParkingService(Jdbi jdbi, HttpShareService httpShareService, FileService fileService, ShareConfig shareConfig) {
        this.jdbi = jdbi;
        this.httpShareService = httpShareService;
        this.fileService = fileService;
        this.shareConfig = shareConfig;
    }

    public void syncParkedFiles(SyncParkingSpaceRequest syncParkingSpaceRequest, User user) {
        MinimalAsset asset = syncParkingSpaceRequest.asset();
        String basePath = shareConfig.mountFolder() + "/assetfiles/" + shareConfig.parkingFolder() + "/" + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid();
        logger.info("Syncing parkingspace: " + basePath);
        File file = new File(basePath);
        // If there are no files in parking space, do nothing. We currently dont allow empty parking space to overwrite files.
        if (file.exists() && file.isDirectory()) {
            logger.info("Found files in parkingspace");
            List<File> files = fileService.listFiles(file, new ArrayList<>(), false, false);
            Optional<Directory> writeableDirectory = fileService.getWriteableDirectory(asset.asset_guid());
            if (writeableDirectory.isPresent()) {
                Directory directory = writeableDirectory.get();
                //deleting existing
                logger.info("Deleting existing: " + directory);
                httpShareService.deleteShare(user, asset.asset_guid());
            }
            long sizeBytes = files.stream().mapToLong(File::length).sum();
            HttpInfo httpInfo = httpShareService.createHttpShareInternal(new CreationObj(List.of(asset), List.of(user.username), (int) (sizeBytes / 1000000)));
            logger.info("Created http share: " + httpInfo);
            if (httpInfo.http_allocation_status() != SUCCESS) {
                throw new RuntimeException("Sync Parked failed to allocate space with status " + httpInfo.http_allocation_status());
            }
            for (File parkedFile : files) {
                try {
                    logger.info("Moving parked file: " + parkedFile);

                    try (InputStream inputStream = Files.newInputStream(parkedFile.toPath())) {
                        File target = new File(shareConfig.mountFolder() + "/assetfiles/"+ asset.institution()+"/"+asset.collection()+"/"+ asset.asset_guid() + "/" + parkedFile.getName());
                        logger.info("Moving file to: " + target);
                        long crc = writeToDiskAndGetCRC(inputStream,target);
                        jdbi.withHandle(h -> {
                            FileRepository fileRepository = h.attach(FileRepository.class);
                            String mimetype;
                            try {
                                mimetype = new Tika().detect(target);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            fileRepository.insertFile(new DasscoFile(null, asset.asset_guid(), "/"+ asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/" +file.getName(), parkedFile.length(), crc, FileSyncStatus.NEW_FILE,mimetype , true));
                            return h;
                        }) ;
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to move files", e);
                }
                //delete the parked file
                parkedFile.delete();
            }
            //sync the share we just opened
            Optional<Directory> directoryToSync = fileService.getWriteableDirectory(asset.asset_guid());
            if (directoryToSync.isPresent()) {
                fileService.scheduleDirectoryForSynchronization(directoryToSync.get().directoryId(), new AssetUpdate(asset.asset_guid(), null, null, user.username), syncParkingSpaceRequest.specifySyncLogId());
            } else {
                throw new RuntimeException("Directory not found");
            }
        }
    }
}
