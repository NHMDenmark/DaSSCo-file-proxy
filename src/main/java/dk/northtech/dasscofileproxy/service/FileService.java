package dk.northtech.dasscofileproxy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.net.UrlEscapers;
import com.nimbusds.jose.shaded.gson.Gson;
import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoInternalErrorException;
import dk.northtech.dasscofileproxy.repository.DirectoryRepository;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import dk.northtech.dasscofileproxy.repository.SharedAssetRepository;
import dk.northtech.dasscofileproxy.repository.UserAccessList;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import dk.northtech.dasscofileproxy.webapi.model.FileUploadResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.stream.Collectors.toList;

@Service
public class FileService {
    ShareConfig shareConfig;
    Jdbi jdbi;
    AssetService assetService;
    AssetServiceProperties assetServiceProperties;
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final ObservationRegistry observationRegistry;

    @Inject
    public FileService(ShareConfig shareConfig, Jdbi jdbi, AssetService assetService,
                       AssetServiceProperties assetServiceProperties,
                       ObservationRegistry observationRegistry) {
        this.shareConfig = shareConfig;
        this.assetService = assetService;
        this.assetServiceProperties = assetServiceProperties;
        this.observationRegistry = observationRegistry;
        this.jdbi = jdbi;
    }


    public Optional<String> createShareFolder(MinimalAsset asset) {
        File newDirectory = new File(shareConfig.mountFolder() + "/assetfiles/" + asset.institution() + "/" + asset.collection() + "/" + asset.asset_guid() + "/");
        if (!newDirectory.exists()) {
            boolean mkdirs = newDirectory.mkdirs();
            if (!mkdirs) {
                Optional.empty();
            }
        }
        return Optional.of(newDirectory.getPath());
    }

    public AssetAllocation getUsageByAsset(MinimalAsset minimalAsset) {
        return Observation.createNotStarted("persist:get-usage-by-asset", observationRegistry).observe(() -> {
            return jdbi.withHandle(h -> {
                long assetAllocation = 0;
                long parentAllocation = 0;
                FileRepository attach = h.attach(FileRepository.class);
                assetAllocation = attach.getTotalAllocatedByAsset(Set.of(minimalAsset.asset_guid()));
                if (!minimalAsset.parent_guids().isEmpty()) {
                    parentAllocation = attach.getTotalAllocatedByAsset(minimalAsset.parent_guids());
                }
                return new AssetAllocation(assetAllocation, parentAllocation);
            });
        });
    }

    public Optional<FileResult> getFile(FileUploadData fileUploadData) {
        File file = new File(shareConfig.mountFolder() + fileUploadData.getAssetFilePath());
        if (file.exists()) {
            FileRepository fileRepository = jdbi.onDemand(FileRepository.class);
            DasscoFile filesByAssetPath = fileRepository.getFilesByAssetPath(fileUploadData.getAssetFilePath());
            try {
                return Optional.of(new FileResult(new FileInputStream(file), file.getName(), filesByAssetPath != null ? filesByAssetPath.mime_type() : null));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else {
            return Optional.empty();
        }
    }

    public Optional<FileResult> getFile(String path) {
        File file = new File(path);
        if (file.exists()) {
            FileRepository fileRepository = jdbi.onDemand(FileRepository.class);
            DasscoFile filesByAssetPath = fileRepository.getFilesByAssetPath(path);
            try {
                return Optional.of(new FileResult(new FileInputStream(file), file.getName(), filesByAssetPath != null ? filesByAssetPath.mime_type() : null));
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
                    String[] splitPath = path.replace("\\", "/")
                            .split(shareConfig.mountFolder());
                    String pathWithoutDir = splitPath.length == 2 ? splitPath[1] : splitPath[0];
                    return shareConfig.nodeHost() + "/file_proxy/api" + UrlEscapers.urlFragmentEscaper().escape(pathWithoutDir);
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

    public boolean deleteFile(String locationOnDisk) {
        File file = new File(locationOnDisk);
        return file.delete();
    }

    public record FileResult(InputStream is, String filename, String mime_type) {}

    public boolean deleteAllFilesFromOriginalInParked(String path) {
        String originalPath = shareConfig.mountFolder() + "/" + shareConfig.parkingFolder() + "/" + path;
        String[] pathParts = path.split("/");
        Path dir = Paths.get(originalPath.replace(pathParts[pathParts.length - 1], "").replace("originals", "thumbnails"));
        String[] filenameParts = pathParts[pathParts.length - 1].split("\\.");

        File file = new File(originalPath.replace("thumbnails", "originals"));
        if (file.exists()) {
            file.delete();
        } else {
            return false;
        }

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

    public long getFoldersize(String directoryName) {
        long size = 0;
        try (Stream<Path> walk = Files.walk(Paths.get(directoryName))) {
            size = walk
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            System.out.printf("Failed to get size of %s%n%s", p, e);
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            throw new DasscoInternalErrorException("Could not get size of folder", e);
        }
        return size;
    }

    public Optional<Directory> getWriteableDirectory(String assetGuid) {
        return jdbi.withHandle(handle -> {
            DirectoryRepository directoryRepository = handle.attach(DirectoryRepository.class);
            List<Directory> writeableDirectoriesByAsset = directoryRepository.getWriteableDirectoriesByAsset(assetGuid);
            if (writeableDirectoriesByAsset.isEmpty()) {
                return Optional.empty();
            }
            if (writeableDirectoriesByAsset.size() > 1) {
                throw new DasscoInternalErrorException("Multiple writeable directories found for asset " + assetGuid + " this has to be fixed manually");
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
            SharedAssetRepository sharedAssetRepository = h.attach(SharedAssetRepository.class);
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
            SharedAssetRepository sharedAssetRepository = h.attach(SharedAssetRepository.class);
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

    public FileUploadResult upload(InputStream file, long crc, FileUploadData fileUploadData, boolean hasThumbnail) {
        fileUploadData.validate();
        if (fileUploadData.filePathAndName().toLowerCase().replace("/", "").startsWith("parents")) {
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
            long totalAllocatedByAsset = fileRepository.getTotalAllocatedByAsset(Set.of(fileUploadData.asset_guid()));
            String fullPath = basePath + fileUploadData.filePathAndName();
//            List<DasscoFile> filesByAssetGuid = fileRepository.getFilesByAssetPath(fullPath);
            if ((totalAllocatedByAsset + fileUploadData.size_mb() * 1000000) / 1000000d > directory.get().allocatedStorageMb()) {
                throw new IllegalArgumentException("Total size of asset files exceeds allocated disk space");
            }
            File file2 = new File(fullPath);
            file2.getParentFile().mkdirs();
            // Mark entry in filetable for deletion after file has been successfully received
            boolean markForDeletion = file2.exists();
            String tempName = "." + System.currentTimeMillis() + ".temp";
            logger.info("Receiving: " + fullPath);
            // Use tempfile so we dont overwrite existing files when crc doesnt match
            File tempFile = new File(fullPath + tempName);
            long value = writeToDiskAndGetCRC(file, tempFile);
            try {
                if (crc == value) {
                    if (markForDeletion) {
                        logger.info("Marking overwritten file for deletion upon sync");
                        fileRepository.markForDeletion(fileUploadData.getPath());
                    }
                    long fileSize = tempFile.length();
                    // Move to actual location and overwrite existing file if present.
                    Files.move(tempFile.toPath(), file2.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    fileRepository.insertFile(new DasscoFile(null, fileUploadData.asset_guid(), fileUploadData.getPath(), fileSize, value, FileSyncStatus.NEW_FILE, fileUploadData.mime_type(), hasThumbnail));
                }

            } catch (IOException e) {
                throw new RuntimeException("Failed to cleanup temp file", e);
            } finally {
                // always cleanup temp file
                tempFile.delete();
            }
            return new FileUploadResult(crc, value);
        });
    }

    public void uploadToParking(InputStream inputStream, String path) {
        String basePath = shareConfig.mountFolder() + "/" + shareConfig.parkingFolder() + "/" + path;
        File file = new File(basePath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        writeToDiskAndGetCRC(inputStream, file);
    }

    public Optional<DasscoFile> getFilePathForAdapterFile(String institution, String collection, String filename, String type, Integer scale) {
        return jdbi.withHandle(handle -> {
            FileRepository fileRepository = handle.attach(FileRepository.class);
            return fileRepository.getFilePathForAdapterFile(institution, collection, filename, type.equals("thumbnails"));
        });
    }

    public Optional<FileResult> readFromParking(String path, Integer scale) {
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
                    return Optional.of(new FileResult(new FileInputStream(file), file.getName(), null));
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
            // Code that generates a Thumbnail
            /*else if (path.contains("/thumbnails/") && this.asList(shareConfig.thumbnailMimeTypes()).contains(mimeType) *//* && check file extension is one of ... *//*) {
            String basePathOriginal = shareConfig.mountFolder() + "/" + shareConfig.parkingFolder() + "/" + path.replace("/thumbnails/", "/originals/").replace("_" + scale + ".", ".");
            File fileOriginal = new File(basePathOriginal);
            if (fileOriginal.exists()) {
                try {
                    InputStream scaledInputStream = this.fileToScaledVersion(fileOriginal, scale, mimeType);
                    if(scaledInputStream == null){
                        return Optional.empty();
                    }
                    if (file.getParentFile() != null) {
                        file.getParentFile().mkdirs();
                    }
                    long crc = writeToDiskAndGetCRC(scaledInputStream, file);
                    if (crc > 0) {
                        return Optional.of(new FileResult(new FileInputStream(file), file.getName()));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }else{
                throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).entity("Missing original: %s".formatted(path)).build());
            }
        }*/
            return Optional.empty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Code that scale a File
/*public InputStream fileToScaledVersion(File inputFile, Integer scale, String mimeType) throws IOException {
    String fileExtension = List.of("application/pdf", "image/tiff").contains(mimeType) ? "png" : inputFile.getName().substring(inputFile.getName().lastIndexOf(".") + 1);
    if(List.of("image/jpeg","image/png", "image/gif", "image/tiff").contains(mimeType)){
        BufferedImage inputImage = ImageIO.read(inputFile);
        int scaledHeight = inputImage.getHeight() > inputImage.getWidth() ? scale : (int)(((double) scale / (double) inputImage.getWidth()) * inputImage.getHeight());
        int scaledWidth = inputImage.getWidth() > inputImage.getHeight() ? scale : (int)(((double) scale / (double) inputImage.getHeight()) * inputImage.getWidth());
        var imageType = inputImage.getType();
        BufferedImage outputImage = new BufferedImage(scaledWidth, scaledHeight, imageType == 0 ? BufferedImage.TYPE_INT_ARGB : imageType);

        Graphics2D g2d = outputImage.createGraphics();
        //speed -> g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        //quality -> g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.drawImage(inputImage, 0, 0, scaledWidth, scaledHeight, null);
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(outputImage, fileExtension, baos);
        baos.flush();
        return new ByteArrayInputStream(baos.toByteArray());

    }
    else if(Objects.equals("application/pdf", mimeType)){
        try (PDDocument document = Loader.loadPDF(new File(inputFile.getPath()))) {
            if(document.getNumberOfPages() > 0){
                PDFRenderer renderer = new PDFRenderer(document);
                BufferedImage inputImage = renderer.renderImageWithDPI(0, 150, ImageType.RGB);
                int scaledHeight = inputImage.getHeight() > inputImage.getWidth() ? scale : (int)(((double) scale / (double) inputImage.getWidth()) * inputImage.getHeight());
                int scaledWidth = inputImage.getWidth() > inputImage.getHeight() ? scale : (int)(((double) scale / (double) inputImage.getHeight()) * inputImage.getWidth());
                BufferedImage outputImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);

                Graphics2D g2d = outputImage.createGraphics();
                //speed -> g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                //quality -> g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2d.drawImage(inputImage, 0, 0, scaledWidth, scaledHeight, null);
                g2d.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(outputImage, fileExtension, baos);
                baos.flush();
                return new ByteArrayInputStream(baos.toByteArray());
            }
        }

    }
    return null;
}*/

    private static long writeToDiskAndGetCRC(InputStream file, File tempFile) {
        long value = 0;
        try (FileOutputStream fileOutput = new FileOutputStream(tempFile)) {
            CRC32 crc32 = new CRC32();
            CheckedInputStream checkedInputStream = new CheckedInputStream(file, crc32);
            checkedInputStream.transferTo(fileOutput);
            value = checkedInputStream.getChecksum().getValue();
        } catch (IOException e) {
            tempFile.delete();
            throw new RuntimeException("Failed to write file", e);
        }
        return value;
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
        File file = new File(shareConfig.mountFolder() + fileUploadData.getAssetFilePath());
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
        Map<String, DasscoFile> pathFileMap = listFilesByAssetGuid(fileUploadData.asset_guid())
                .stream()
                .filter(x -> !x.deleteAfterSync())
                .collect(Collectors.toMap(dasscoFile -> shareConfig.mountFolder() + dasscoFile.getWorkDirFilePath(), x -> x));
        if (file.isDirectory()) {
            try (var dirStream = Files.walk(Paths.get(shareConfig.mountFolder() + fileUploadData.getAssetFilePath()))) {
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
                                if (pathFileMap.containsKey(normalisedPath)) {
                                    markDasscoFileToBeDeleted(pathFileMap.get(normalisedPath).path());
                                }
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete file", e);
            }
        } else {
            file.delete();
            String normalisedPath = file.toString().replace('\\', '/');
            if (pathFileMap.containsKey(normalisedPath)) {
                markDasscoFileToBeDeleted(pathFileMap.get(normalisedPath).path());
            }
        }
        return true;
    }

    public boolean deleteLocalFiles(String relativePath, String fileName) {
        String projectDir = System.getProperty("user.dir");
        Path filePath = Paths.get(projectDir, "target", relativePath);
        File file = new File(filePath.toString());

        if (file.exists() && file.getName().equals(fileName)) {
            try {
                Files.delete(filePath);
                return true;
            } catch (IOException e) {
                logger.error(e.getMessage());
                return false;
            }
        } else {
            return false;
        }
    }

    public void createZipFile(String guid) throws IOException {

        String projectDir = System.getProperty("user.dir");
        Path tempDir = Paths.get(projectDir, "target", "temp", guid);
        Path zipFilePath = tempDir.resolve("assets.zip");

        try (FileOutputStream fos = new FileOutputStream(zipFilePath.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            Files.walk(tempDir)
                    .filter(path -> !path.equals(zipFilePath))
                    .forEach(path -> {
                        String entryName = tempDir.relativize(path).toString();
                        if (Files.isDirectory(path)) {
                            try {
                                zos.putNextEntry(new ZipEntry(entryName + "/"));
                                zos.closeEntry();
                            } catch (IOException e) {
                                logger.error(e.getMessage());
                            }
                        } else {
                            try {
                                ZipEntry zipEntry = new ZipEntry(entryName);
                                zos.putNextEntry(zipEntry);
                                Files.copy(path, zos);
                                zos.closeEntry();
                            } catch (Exception e) {
                                logger.error(e.getMessage());
                            }
                        }
                    });
        }
    }

    public boolean checkAccess(String assetGuid, User user) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(assetServiceProperties.rootUrl() + "/api/v1/assets/readaccess?assetGuid=" + assetGuid))
                .header("Authorization", "Bearer " + user.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                return false;
            } else if (response.statusCode() == 204) {
                return true;
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
        return false;
    }
    public Optional<DasscoFile> getDasscoFileThumbnailForGuid(String assetGuid) {
        return this.jdbi.onDemand(FileRepository.class).getFileThumbnailByAssetGuid(assetGuid);
    }
    public Optional<DasscoFile> getDasscoFileForGuid(String assetGuid) {
        return this.jdbi.onDemand(FileRepository.class).getFileByAssetGuid(assetGuid);
    }

    public List<DasscoFile> getDasscoFiles(List<String> assets, User user, String guid) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }

        Gson gson = new Gson();
        String requestBody = gson.toJson(assets);

        ObjectMapper objectMapper = new ObjectMapper();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(assetServiceProperties.rootUrl() + "/api/v1/assets/readaccessforzip"))
                .header("Authorization", "Bearer " + user.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                // throw something
            } else if (response.statusCode() == 200) {
                Set<String> assetGuids = objectMapper.readValue(response.body(), new TypeReference<Set<String>>() {
                });
                return jdbi.onDemand(FileRepository.class).getSyncFilesByAssetGuids(assetGuids);
            } else {
                // throw something
                return List.of();
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
        // throw something
        return List.of();

    }

    public Response checkAccessCreateZip(List<String> assets, User user, String guid) {

        if (assets == null || assets.isEmpty()) {
            return Response.status(500).entity("Need to pass a list of assets").build();
        }

        Gson gson = new Gson();
        String requestBody = gson.toJson(assets);

        ObjectMapper objectMapper = new ObjectMapper();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(assetServiceProperties.rootUrl() + "/api/v1/assets/readaccessforzip"))
                .header("Authorization", "Bearer " + user.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 403) {
                return Response.status(403).entity(response.body()).build();
            } else if (response.statusCode() == 200) {
                try {
                    // GET FILE LOCATION FROM THE DB:
                    Set<String> assetGuids = objectMapper.readValue(response.body(), new TypeReference<Set<String>>() {
                    });
                    List<DasscoFile> dasscoFiles = jdbi.onDemand(FileRepository.class).getSyncFilesByAssetGuids(assetGuids);
                    List<String> assetFiles = dasscoFiles.stream().map(DasscoFile::path).collect(Collectors.toList());
                    if (!assetFiles.isEmpty()) {
                        saveFilesTempFolder(assetFiles, user, guid);
                    }
                    this.createZipFile(guid);
                    return Response.status(200).entity(guid).build();
                } catch (Exception e) {
                    logger.error(e.getMessage());
                }
            } else {
                return Response.status(500).entity(response.body()).build();
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
        return Response.status(500).entity("There was an error downloading the files").build();
    }

    public Response checkAccessCreateCSV(List<String> assets, User user) {

        if (assets == null || assets.isEmpty()) {
            return Response.status(500).entity("Need to pass a list of assets").build();
        }

        Gson gson = new Gson();
        String requestBody = gson.toJson(assets);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(assetServiceProperties.rootUrl() + "/api/v1/assets/readaccessforcsv"))
                .header("Authorization", "Bearer " + user.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 403) {
                return Response.status(403).entity(response.body()).build();
            } else if (response.statusCode() == 200) {
                // Create a 20 digit guid
                String guid = randomGuidGenerator(20);
                // Create the CSV file:
                createCsvFile(response.body(), guid);
                return Response.status(200).entity(guid).build();
            } else {
                return Response.status(500).entity(response.body()).build();
            }

        } catch (Exception e) {
            logger.error(e.getMessage());
        }
        return Response.status(500).build();
    }

    public void createCsvFile(String csvString, String guid) {
        String separatorLine = "sep=,\r\n";
        String fullCsv = separatorLine + csvString;
        String basePath = shareConfig.mountFolder();
        Path tempDir = Paths.get(basePath, "temp", guid);
        try {
            Files.createDirectories(tempDir);
            Path filePath = tempDir.resolve("assets.csv");
            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(fullCsv);
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
    }

    public void saveFilesTempFolder(List<String> paths, User user, String guid) throws IOException, InterruptedException {

        String projectDir = System.getProperty("user.dir");
        Path tempDir = Paths.get(projectDir, "target", "temp", guid);

        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            logger.error(e.getMessage());
        }

        for (String path : paths) {
            String[] parts = path.split("/");
            String folderName = parts[parts.length - 2];
            String fileName = parts[parts.length - 1];

            Path outputDir = tempDir.resolve(folderName);
            Files.createDirectories(outputDir);

            String encodedPath = UriUtils.encodePath(path, "UTF-8");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(shareConfig.nodeHost() + "/file_proxy/api/files/assets" + encodedPath))
                    .header("Authorization", "Bearer " + user.token)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    Path outputPath = outputDir.resolve(fileName);
                    try (InputStream inputStream = response.body()) {
                        Files.copy(inputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    throw new FileNotFoundException("Failed to download image: " + path);
                }
            } catch (Exception e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
    }

    // Clean the temp folder (if it exists) from .zip and folders.
    public void cleanTempFolder() {
        String projectDir = System.getProperty("user.dir");
        Path tempDir = Paths.get(projectDir, "target", "temp");

        if (Files.exists(tempDir)) {
            try {
                try (var stream = Files.list(tempDir)) {
                    stream.filter(path -> path.toString().endsWith(".zip"))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    logger.error(e.getMessage());
                                }
                            });
                }

                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .filter(path -> !path.equals(tempDir))
                            .filter(path -> Files.isDirectory(path) || !path.toString().endsWith(".csv"))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    logger.error(e.getMessage());
                                }
                            });
                }
            } catch (IOException e) {
                logger.error(e.getMessage());
            }
        }
    }

    public List<String> listFilesInErda(String assetGuid) {
        List<DasscoFile> files = jdbi.onDemand(FileRepository.class).getSyncFilesByAssetGuid(assetGuid);
        return files.stream().map(DasscoFile::getWorkDirFilePath).toList();
    }

    public String randomGuidGenerator(int length) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();

        StringBuilder guid = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            guid.append(characters.charAt(random.nextInt(characters.length())));
        }
        return guid.toString();
    }

    private List<String> asList(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).collect(toList());
    }
}
