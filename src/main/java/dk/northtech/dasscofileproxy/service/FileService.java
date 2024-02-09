package dk.northtech.dasscofileproxy.service;

import com.google.common.base.Strings;
import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.repository.DirectoryRepository;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

@Service
public class FileService {
    DockerConfig dockerConfig;
    ShareConfig shareConfig;
    Jdbi jdbi;
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    @Inject
    public FileService(DockerConfig dockerConfig, ShareConfig shareConfig, Jdbi jdbi) {
        this.dockerConfig = dockerConfig;
        this.shareConfig = shareConfig;
        this.jdbi = jdbi;
    }


    public String createShareFolder(MinimalAsset asset) {
        File newDirectory = new File(shareConfig.mountFolder() + "assetfiles/" + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/");
        if (!newDirectory.exists()) {
            newDirectory.mkdirs();
        }
        return newDirectory.getPath();
    }


    public void removeShareFolder(Long shareId) {
        if (Strings.isNullOrEmpty(dockerConfig.mountFolder())) {
            throw new RuntimeException("Cannot delete share folder, mountFolder is null");
        }
        Path path = Path.of(dockerConfig.mountFolder() + "share_" + shareId);
        deleteAll(path.toFile());
    }

    public List<File> listFiles(File directory, List<File> files) {
        File[] fList = directory.listFiles();
        if (fList != null)
            for (File file : fList) {
                if (file.isFile()) {
                    files.add(file);
                } else if (file.isDirectory() && !file.getName().contains("parent")) {
                    listFiles(new File(file.getAbsolutePath()), files);
                }
            }
        return files;
    }

    void deleteAll(File dir) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory())
                deleteAll(file);
            file.delete();
        }
        dir.delete();
    }

    public Directory getWriteableDirectory(FileUploadData fileUploadData) {
        return jdbi.withHandle(handle -> {
            DirectoryRepository directoryRepository = handle.attach(DirectoryRepository.class);
            List<Directory> writeableDirectoriesByAsset = directoryRepository.getWriteableDirectoriesByAsset(fileUploadData.asset_guid());
            if (writeableDirectoriesByAsset.size() != 1) {
                throw new IllegalArgumentException("No writeable directory found for asset.");
            }
            return writeableDirectoriesByAsset.get(0);
        });
    }

    public FileUploadResult upload(InputStream file, long crc, FileUploadData fileUploadData) {
        fileUploadData.validate();
        if (fileUploadData.filePathAndName().toLowerCase().replace("/", "").startsWith("parent")) {
            throw new IllegalArgumentException("File path cannot start with 'parent'");
        }
        Directory directory = getWriteableDirectory(fileUploadData);
        String basePath = shareConfig.mountFolder() + fileUploadData.getBasePath();
        File file1 = new File(basePath);
        if (!file1.exists()) {
            throw new IllegalArgumentException("Share directory doesnt exist");
        }
        return jdbi.inTransaction(h -> {
            FileRepository fileRepository = h.attach(FileRepository.class);
            long totalAllocatedByAsset = fileRepository.getTotalAllocatedByAsset(fileUploadData.asset_guid());
            String fullPath = basePath + fileUploadData.filePathAndName();
//            List<DasscoFile> filesByAssetGuid = fileRepository.getFilesByAssetPath(fullPath);
            if ((totalAllocatedByAsset + fileUploadData.size_mb() * 1000000) / 1000000d > directory.allocatedStorageMb()) {
                throw new IllegalArgumentException("Total size of asset files exceeds allocated disk space");
            }
            File file2 = new File(fullPath);
            file2.getParentFile().mkdirs();
            // Mark entry in filetable for deletion after file has been successfully received
            boolean markForDeletion = file2.exists();
            try (FileOutputStream fileOutput = new FileOutputStream(file2)) {
                CRC32 crc32 = new CRC32();
                CheckedInputStream checkedInputStream = new CheckedInputStream(file, crc32);
                checkedInputStream.transferTo(fileOutput);
                long value = checkedInputStream.getChecksum().getValue();
                if(markForDeletion) {
                    fileRepository.markForDeletion(fullPath);
                }
                fileRepository.insertFile(new DasscoFile(null, fileUploadData.asset_guid(), fullPath, file2.length(),value));
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

    public boolean deleteFile(FileUploadData fileUploadData) {
        File file = new File(shareConfig.mountFolder() + fileUploadData.getFilePath());
        if (file.exists()) {
            if (file.isDirectory()) {
                deleteAll(file);
                return true;
            } else {
                return file.delete();
            }
        } else {
            return false;
        }
    }
}
