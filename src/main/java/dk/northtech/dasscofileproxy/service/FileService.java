package dk.northtech.dasscofileproxy.service;

import com.google.common.base.Strings;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.DirectoryRepository;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import dk.northtech.dasscofileproxy.repository.SharedAssetList;
import dk.northtech.dasscofileproxy.repository.UserAccessList;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

@Service
public class FileService {
    ShareConfig shareConfig;
    Jdbi jdbi;
    AssetService assetService;
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    @Inject
    public FileService(ShareConfig shareConfig, Jdbi jdbi, AssetService assetService) {
        this.shareConfig = shareConfig;
        this.assetService = assetService;
        this.jdbi = jdbi;
    }


    public String createShareFolder(MinimalAsset asset) {
        File newDirectory = new File(shareConfig.mountFolder() + "/assetfiles/" + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/");
        if (!newDirectory.exists()) {
            newDirectory.mkdirs();
        }
        return newDirectory.getPath();
    }

    public AssetAllocation getUsageByAsset(MinimalAsset minimalAsset) {
        return jdbi.withHandle(h -> {
            long assetAllocation = 0;
            long parentAllocation = 0;
            FileRepository attach = h.attach(FileRepository.class);
            assetAllocation = attach.getTotalAllocatedByAsset(minimalAsset.asset_guid());
            if (minimalAsset.parent_guid() != null) {
                parentAllocation = attach.getTotalAllocatedByAsset(minimalAsset.parent_guid());
            }
            return new AssetAllocation(assetAllocation, parentAllocation);
        });
    }

    public Optional<FileResult> getFile(FileUploadData fileUploadData) {
        File file = new File(shareConfig.mountFolder() + fileUploadData.getFilePath());
        if (file.exists()) {
            try {
                return Optional.of(new FileResult(new FileInputStream(file), file.getName()));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else {
            return Optional.empty();
        }
    }

    public List<String> listAvailableFiles(FileUploadData fileUploadData) {
        List<String> links = new ArrayList<>();
        File file = new File(shareConfig.mountFolder() + fileUploadData.getBasePath());
        return listFiles(file, new ArrayList<>(), true, false)
                .stream()
                .map(f -> {
                    String path = f.toString();
                    String pathWithoutDir = path.replace("\\", "/")
                            .replace(shareConfig.mountFolder(), "");
                    return shareConfig.nodeHost() + "/file_proxy/api" + pathWithoutDir;
                })
                .collect(Collectors.toList());
    }

    public void markFilesAsSynced(String assetGuid) {
        jdbi.withHandle(h -> {
            FileRepository fileRepository = h.attach(FileRepository.class);
            fileRepository.setSynchronizedStatus(assetGuid);
            return h;
        });
    }

    public record FileResult(InputStream is, String filename) {
    }

    ;

    record AssetAllocation(long assetBytes, long parentBytes) {
        int getTotalAllocationAsMb() {
            return (int) Math.ceil((assetBytes + parentBytes) / 1000000d);
        }

        int getParentAllocationAsMb() {
            return (int) Math.ceil((parentBytes) / 1000000d);
        }
    }

    public void removeShareFolder(Directory directory) {
        //avoid deleting everything in the filesystem
        if (Strings.isNullOrEmpty(shareConfig.mountFolder())) {
            throw new RuntimeException("Cannot delete share folder, mountFolder is null");
        }
        Path path = Path.of(shareConfig.mountFolder() + directory.uri());
        File file = path.toFile();
        if (file.exists()) {
            deleteAll(file);
        }
    }

    public List<File> listFiles(File directory, List<File> files, boolean includeParents, boolean includeFolders) {
        File[] fList = directory.listFiles();
        if (fList != null)
            for (File file : fList) {
                if (file.isFile()) {
                    files.add(file);
                } else if (file.isDirectory() && (!file.getName().contains("parent") || includeParents)) {
                    listFiles(new File(file.getAbsolutePath()), files, includeParents, includeFolders);
                }
            }
        return files;
    }

    void deleteAll(File dir) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                deleteAll(file);
            }
            file.delete();
        }
        dir.delete();
    }


    public Optional<Directory> getWriteableDirectory(String assetGuid) {
        return jdbi.withHandle(handle -> {
            DirectoryRepository directoryRepository = handle.attach(DirectoryRepository.class);
            List<Directory> writeableDirectoriesByAsset = directoryRepository.getWriteableDirectoriesByAsset(assetGuid);
            if (writeableDirectoriesByAsset.size() != 1) {
                return Optional.empty();
            }
            return Optional.of(writeableDirectoriesByAsset.get(0));
        });
    }

    public void scheduleDirectoryForSynchronization(long directoryId, AssetUpdate assetUpdate) {
        jdbi.withHandle(h -> {
            DirectoryRepository attach = h.attach(DirectoryRepository.class);
            attach.scheduleDiretoryForSynchronization(directoryId, assetUpdate);
            return h;
        }).close();
        assetService.setAssestStatus(assetUpdate.assetGuid(), InternalStatus.ASSET_RECEIVED, null);
    }

    public void deleteFilesMarkedAsDeleteByAsset(String asset_guid) {
        jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            attach.deleteFilesMarkedForDeletionByAssetGuid(asset_guid);
            return h;
        }).close();
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
        }).close();
    }

    public void resetDirectoryAndResetFiles(long directoryId, String assetGuid) {
        jdbi.inTransaction(h -> {
            SharedAssetList sharedAssetRepository = h.attach(SharedAssetList.class);
            UserAccessList userAccessRepository = h.attach(UserAccessList.class);
            DirectoryRepository directoryRepository = h.attach(DirectoryRepository.class);
            FileRepository fileRepository = h.attach(FileRepository.class);
            fileRepository.resetDeleteFlag(assetGuid);
            fileRepository.deleteNewFiles(assetGuid);
            userAccessRepository.deleteUserAccess(directoryId);
            sharedAssetRepository.deleteSharedAsset(directoryId);
            directoryRepository.deleteSharedAsset(directoryId);
            return h;
        }).close();
    }

    public FileUploadResult upload(InputStream file, long crc, FileUploadData fileUploadData) {
        fileUploadData.validate();
        if (fileUploadData.filePathAndName().toLowerCase().replace("/", "").startsWith("parent")) {
            throw new IllegalArgumentException("File path cannot start with 'parent'");
        }
        Optional<Directory> directory = getWriteableDirectory(fileUploadData.asset_guid());
        if (directory.isEmpty()) {
            throw new IllegalArgumentException("There is no writeable directory for this asset");
        }
        String basePath = shareConfig.mountFolder() + "/" + fileUploadData.getBasePath();
        File file1 = new File(basePath);
        if (!file1.exists()) {
            throw new IllegalArgumentException("Share directory doesnt exist");
        }
        return jdbi.inTransaction(h -> {
            FileRepository fileRepository = h.attach(FileRepository.class);
            long totalAllocatedByAsset = fileRepository.getTotalAllocatedByAsset(fileUploadData.asset_guid());
            String fullPath = basePath + fileUploadData.filePathAndName();
//            List<DasscoFile> filesByAssetGuid = fileRepository.getFilesByAssetPath(fullPath);
            if ((totalAllocatedByAsset + fileUploadData.size_mb() * 1000000) / 1000000d > directory.get().allocatedStorageMb()) {
                throw new IllegalArgumentException("Total size of asset files exceeds allocated disk space");
            }
            File file2 = new File(fullPath);
            file2.getParentFile().mkdirs();
            // Mark entry in filetable for deletion after file has been successfully received
            boolean markForDeletion = file2.exists();
            logger.info("Receiving: " + fullPath);
            try (FileOutputStream fileOutput = new FileOutputStream(file2)) {
                CRC32 crc32 = new CRC32();
                CheckedInputStream checkedInputStream = new CheckedInputStream(file, crc32);
                checkedInputStream.transferTo(fileOutput);
                long value = checkedInputStream.getChecksum().getValue();
                if (markForDeletion) {
                    logger.info("Marking overwritten file for deletion upon sync");
                    fileRepository.markForDeletion(fileUploadData.getFilePath());
                }
                fileRepository.insertFile(new DasscoFile(null, fileUploadData.asset_guid(), fileUploadData.getFilePath(), file2.length(), value, FileSyncStatus.NEW_FILE));
                return new FileUploadResult(crc, value);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write file", e);
            }
        });
    }

    public List<DasscoFile> listFilesByAssetGuid(String assetGuid) {

        return jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            return attach.getFilesByAssetGuid(assetGuid);
        });
    }

    public void markDasscoFileToBeDeleted(String path) {
        jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            attach.markForDeletion(path);
            return h;
        }).close();
    }

    public boolean deleteFile(FileUploadData fileUploadData) {
        File file = new File(shareConfig.mountFolder() + fileUploadData.getFilePath());
        jdbi.withHandle(h -> {
            DirectoryRepository directoryRepository = h.attach(DirectoryRepository.class);
            List<Directory> writeableDirectoriesByAsset = directoryRepository.getWriteableDirectoriesByAsset(fileUploadData.asset_guid());
            if (writeableDirectoriesByAsset.size() != 1) {
                throw new IllegalArgumentException("No writable directory was found for asset");
            }
            return h;
        }).close();
        System.out.println(file);
        if (!file.exists()) {
            logger.info("File or folder did not exist");
            return false;
        }
        List<File> files = file.isDirectory() ? listFiles(file, new ArrayList<>(), false, true) : Arrays.asList(file);
        files.forEach(file1 -> {
            System.out.println("Deleting " + file1);
        });
        if (Strings.isNullOrEmpty(fileUploadData.asset_guid())) {
            throw new IllegalArgumentException("Asset guid must be present");
        }
        if (Strings.isNullOrEmpty(fileUploadData.collection())) {
            throw new IllegalArgumentException("Collection must be present");
        }
        // Get all asset files so we can schedule the deleted files for deletion
        Map<String, DasscoFile> collect = listFilesByAssetGuid(fileUploadData.asset_guid())
                .stream()
                .filter(x -> !x.deleteAfterSync())
                .collect(Collectors.toMap(x -> shareConfig.mountFolder() + x.path(), x -> x));
        if (file.isDirectory()) {
            try (var dirStream = Files.walk(Paths.get(shareConfig.mountFolder() + fileUploadData.getFilePath()))) {
                dirStream
                        .map(Path::toFile)
                        .sorted(Comparator.reverseOrder())
                        //Prevents the deletion of the base folder
                        .filter(z -> (!file.toString().equals(z.toString()) || fileUploadData.filePathAndName() != null))
                        .filter(x -> !x.toString().contains("parent"))
                        .forEach(file1 -> {
                            boolean isFile = Files.isRegularFile(file1.toPath());
                            file1.delete();
                            if (isFile) {
                                String normalisedPath = file1.toString().replace('\\', '/');
                                if (collect.containsKey(normalisedPath)) {
                                    markDasscoFileToBeDeleted(collect.get(normalisedPath).path());
                                }
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete file", e);
            }
        } else {
            file.delete();
            String normalisedPath = file.toString().replace('\\', '/');
            if (collect.containsKey(normalisedPath)) {
                markDasscoFileToBeDeleted(collect.get(normalisedPath).path());
            }
        }
        return true;
    }
}
