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
        File newDirectory = new File( shareConfig.mountFolder() + "assetfiles/" + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/");
        if(!newDirectory.exists()){
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
        if(fList != null)
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
        for (File file: dir.listFiles()) {
            if (file.isDirectory())
                deleteAll(file);
            file.delete();
        }
        dir.delete();
    }

    public void validateDirectory(FileUploadData fileUploadData) {
        jdbi.withHandle(handle -> {
            DirectoryRepository directoryRepository = handle.attach(DirectoryRepository.class);
            Directory directory = directoryRepository.getDirectory(fileUploadData.directoryId());
            if(directory.sharedAssets().size() != 1) {
                throw new IllegalArgumentException("Directory can only have one shared asset");
            }
            SharedAsset sharedAsset = directory.sharedAssets().get(0);
            if(!fileUploadData.asset_guid().equals(sharedAsset.assetGuid())) {
                throw new IllegalArgumentException("Asset guid in path doesnt match asset guid of directory");
            }
            if(!directory.access().equals(AccessType.WRITE) && !directory.access().equals(AccessType.ADMIN)) {
                throw  new RuntimeException("Share is not writeable");
            }
            return handle;
        });
    }
    public FileUploadResult upload(InputStream file, long crc, FileUploadData fileUploadData) {
        fileUploadData.validate();
        if(fileUploadData.filePathAndName().toLowerCase().replace("/", "").startsWith("parent")) {
            throw new IllegalArgumentException("File path cannot start with 'parent'");
        }
        validateDirectory(fileUploadData);
        CRC32 crc32 = new CRC32();
        String filePath = fileUploadData.getFilePath();
        File file1 = new File(fileUploadData.getBasePath());
        File fileChecksum = new File(filePath + ".checksum");
        if(!file1.exists()) {
            throw new IllegalArgumentException("Share directory doesnt exist");
        }
        File file2 = new File(filePath);
        try (FileOutputStream fileOutput = new FileOutputStream(file2); FileWriter fileWriter = new FileWriter(fileChecksum);){
            CheckedInputStream checkedInputStream = new CheckedInputStream(file,crc32);
            checkedInputStream.transferTo(fileOutput);
            long value = checkedInputStream.getChecksum().getValue();
            fileWriter.write(value + "");
            return new FileUploadResult(crc, value);

        } catch (IOException e) {
            throw new RuntimeException("Failed to write file");
        }
    }

    public List<DasscoFile> listFilesByAssetGuid(String assetGuid) {
        return jdbi.withHandle(h -> {
            FileRepository attach = h.attach(FileRepository.class);
            return attach.getFilesByAssetGuid(assetGuid);
        });
    }
}
