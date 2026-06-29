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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    public boolean syncParkedFiles(SyncParkingSpaceRequest syncParkingSpaceRequest, User user) {
        logger.info("Sync parked files: {}", syncParkingSpaceRequest);
        MinimalAsset asset = syncParkingSpaceRequest.asset;
        String basePath = shareConfig.mountFolder() + "/" + shareConfig.parkingFolder() + "/" + asset.institution() + "/" + asset.collection() + "/" + syncParkingSpaceRequest.attachmentLocation;
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
            // Make sure we have wiggle room.
            long sizeBytes = files.stream().mapToLong(File::length).sum() + 1000000;
            HttpInfo httpInfo = httpShareService.createHttpShareInternal(new CreationObj(List.of(asset), List.of(user.username), (int) (sizeBytes / 1000000)));
            logger.info("Created http share: " + httpInfo);
            if (httpInfo.http_allocation_status() != SUCCESS) {
                logger.error("Failed to allocate space for syncing parking space: {}", httpInfo.toString());
                throw new RuntimeException("Sync Parked failed to allocate space with status " + httpInfo.http_allocation_status());
            }
            for (File parkedFile : files) {
                try {
                    logger.info("Moving parked file: " + parkedFile);
                    try (InputStream inputStream = Files.newInputStream(parkedFile.toPath())) {
                        String renamedFileName = getRenamedParkedFileName(parkedFile.getName(), syncParkingSpaceRequest.attachmentLocation, asset.asset_guid(), files.size() == 1);
                        File target = new File(shareConfig.mountFolder() + "/assetfiles/" + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/" + renamedFileName);
                        logger.info("Moving file to: " + target);
                        long crc = writeToDiskAndGetCRC(inputStream, target);
                        jdbi.withHandle(h -> {
                            FileRepository fileRepository = h.attach(FileRepository.class);
                            String mimetype;
                            try {
                                mimetype = new Tika().detect(target);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            fileRepository.insertFile(new DasscoFile(null, asset.asset_guid(), "/" + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/" + renamedFileName, parkedFile.length(), crc, FileSyncStatus.NEW_FILE, mimetype, true));
                            return h;
                        });
                        deleteParkedFileMetadata(parkedFile.getPath());
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
                fileService.scheduleDirectoryForSynchronization(directoryToSync.get().directoryId(), new AssetUpdate(asset.asset_guid(), null, null, user.username), syncParkingSpaceRequest.specifySyncLogId);
                return true;
            } else {
                throw new RuntimeException("Directory not found");
            }
        }
        return false;
    }

    public void uploadToParking(InputStream inputStream, String path) {
        String basePath = shareConfig.mountFolder() + "/" + shareConfig.parkingFolder() + "/" + path;
        File file = new File(basePath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        writeToDiskAndGetCRC(inputStream, file);
        String parkedPath = normalizeParkedFilePath(path);
        upsertParkedFileMetadata(parkedPath, file.length());
    }

    public boolean deleteAllFilesFromOriginalInParked(String path) {
        String originalPath = shareConfig.mountFolder() + "/" + shareConfig.parkingFolder() + "/" + path;
        String[] pathParts = path.split("/");
        Path dir = Path.of(originalPath.replace(pathParts[pathParts.length - 1], "").replace("originals", "thumbnails"));
        String[] filenameParts = pathParts[pathParts.length - 1].split("\\.");

        File file = new File(originalPath.replace("thumbnails", "originals"));
        if (file.exists()) {
            file.delete();
        } else {
            return false;
        }
        deleteParkedFileMetadata(path);

        // Thumbnails have been pulled out of the parking spot, no need for this, waiting final confirm.
    /*try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, filenameParts[0] + "_*." + filenameParts[1])) {
        for (Path entry : stream) {
            try {
                Files.deleteIfExists(entry);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    } catch (IOException e) {
        throw new RuntimeException(e);
    }*/

        return true;
    }

    public Optional<FileService.FileResult> readFromParking(String path, Integer scale) {
        try {
            String basePath = shareConfig.mountFolder() + "/" + shareConfig.parkingFolder() + "/" + path;
            File file = new File(basePath);
            String mimeType = Files.probeContentType(file.toPath());
            if (Objects.equals("application/pdf", mimeType) && path.contains("/thumbnails/")) {
                file = new File(basePath.replace(".pdf", ".png"));
            }
            if (Objects.equals("image/tiff", mimeType) && path.contains("/thumbnails/")) {
                file = new File(basePath.replace(".tiff", ".png"));
            }

            if (file.exists()) {
                try {
                    return Optional.of(new FileService.FileResult(new FileInputStream(file), file.getName(), null));
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteParkedFileMetadata(String path) {
        String parkedPath = normalizeParkedFilePath(path);
        jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM parked_file WHERE path = :path")
                .bind("path", parkedPath)
                .execute());
    }

    private void upsertParkedFileMetadata(String path, long sizeBytes) {
        jdbi.withHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO parked_file(path, size_bytes, "timestamp")
                        VALUES (:path, :sizeBytes, now())
                        ON CONFLICT (path)
                        DO UPDATE SET size_bytes = EXCLUDED.size_bytes, "timestamp" = now()
                        """)
                .bind("path", path)
                .bind("sizeBytes", sizeBytes)
                .execute());
    }

    private String normalizeParkedFilePath(String path) {
        String normalizedPath = path.replace('\\', '/').trim();
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        String mountFolder = shareConfig.mountFolder().replace('\\', '/');
        String parkingFolder = shareConfig.parkingFolder().replace('\\', '/');

        if (normalizedPath.startsWith(mountFolder + "/")) {
            normalizedPath = normalizedPath.substring((mountFolder + "/").length());
        }
        if (normalizedPath.startsWith("assetfiles/" + parkingFolder + "/")) {
            normalizedPath = normalizedPath.substring(("assetfiles/" + parkingFolder + "/").length());
        }
        if (normalizedPath.startsWith(parkingFolder + "/")) {
            normalizedPath = normalizedPath.substring((parkingFolder + "/").length());
        }

        int parkingSegmentIndex = normalizedPath.indexOf("/" + parkingFolder + "/");
        if (parkingSegmentIndex >= 0) {
            normalizedPath = normalizedPath.substring(parkingSegmentIndex + parkingFolder.length() + 2);
        }

        return normalizedPath;
    }

    static String getRenamedParkedFileName(String parkedFileName, String attachmentLocation, String assetGuid, boolean singleParkedFile) {
        String attachmentToken = getAttachmentLocationTokenWithoutExtension(attachmentLocation);

        if (!attachmentToken.isBlank() && parkedFileName.contains(attachmentToken)) {
            return parkedFileName.replace(attachmentToken, assetGuid);
        }

        if (singleParkedFile) {
            int extensionIndex = parkedFileName.lastIndexOf('.');
            if (extensionIndex > 0 && extensionIndex < parkedFileName.length() - 1) {
                return assetGuid + parkedFileName.substring(extensionIndex);
            }
            return assetGuid;
        }

        return parkedFileName;
    }

    static String getAttachmentLocationTokenWithoutExtension(String attachmentLocation) {
        if (attachmentLocation == null) {
            return "";
        }
        String token = attachmentLocation.trim();
        if (token.isBlank()) {
            return "";
        }
        int extensionIndex = token.lastIndexOf('.');
        if (extensionIndex > 0) {
            return token.substring(0, extensionIndex);
        }
        return token;
    }
}
