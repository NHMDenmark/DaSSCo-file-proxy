package dk.northtech.dasscofileproxy.service;

import com.google.gson.Gson;
import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.User;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Service
public class AssetBundleCreatorService implements AssetBundleCreator, ExternalAssetBundleCreator {
    private static final Logger logger = LoggerFactory.getLogger(AssetBundleCreatorService.class);

    private final FileService fileService;
    private final CacheFileService cacheFileService;
    private final AssetServiceProperties assetServiceProperties;

    @Inject
    public AssetBundleCreatorService(FileService fileService, CacheFileService cacheFileService, AssetServiceProperties assetServiceProperties) {
        this.fileService = fileService;
        this.cacheFileService = cacheFileService;
        this.assetServiceProperties = assetServiceProperties;
    }

    @Override
    public File create(List<String> assetGuids, User user) throws Exception {
        HttpResponse<String> assetsResponse = fetchAssets(assetGuids, user);
        if (assetsResponse.statusCode() == 403) {
            throw new AssetBundleCreationException("User does not have read access to one or more assets");
        }
        if (assetsResponse.statusCode() < 200 || assetsResponse.statusCode() >= 300) {
            throw new AssetBundleCreationException("Asset service returned HTTP " + assetsResponse.statusCode());
        }

        Map<String, String> metadataCsvByAsset = createMetadataCsvByAsset(assetsResponse.body(), assetGuids);
        Map<String, List<DasscoFile>> filesByAsset = fileService.getDasscoFiles(assetGuids, user)
                .stream()
                .collect(Collectors.groupingBy(DasscoFile::assetGuid));

        File tempZipFile = File.createTempFile("asset-bundle-", ".zip");
        tempZipFile.deleteOnExit();
        try {
            createAssetBundleZip(tempZipFile, assetGuids, metadataCsvByAsset, filesByAsset, user);
        } catch (Exception e) {
            if (tempZipFile.exists() && !tempZipFile.delete()) {
                logger.warn("Failed to delete partial asset bundle ZIP {}", tempZipFile.getAbsolutePath());
            }
            throw e;
        }
        return tempZipFile;
    }

    @Override
    public File createExternal(List<String> assetGuids, User user) throws Exception {
        Map<String, String> metadataCsvByAsset = createExternalMetadataCsvByAsset(assetGuids);
        Map<String, List<DasscoFile>> filesByAsset = assetGuids.stream()
                .flatMap(assetGuid -> fileService.listFilesByAssetGuid(assetGuid).stream())
                .collect(Collectors.groupingBy(DasscoFile::assetGuid));

        File tempZipFile = File.createTempFile("asset-bundle-extern-", ".zip");
        tempZipFile.deleteOnExit();
        try {
            createAssetBundleZip(tempZipFile, assetGuids, metadataCsvByAsset, filesByAsset, user);
        } catch (Exception e) {
            if (tempZipFile.exists() && !tempZipFile.delete()) {
                logger.warn("Failed to delete partial external asset bundle ZIP {}", tempZipFile.getAbsolutePath());
            }
            throw e;
        }
        return tempZipFile;
    }

    private HttpResponse<String> fetchAssets(List<String> assetGuids, User user) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(assetServiceProperties.rootUrl() + "/api/v1/assets/list"))
                .header("Content-Type", APPLICATION_JSON)
                .header("Accept", APPLICATION_JSON)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(assetGuids)));
        if (user.token != null) {
            requestBuilder.header("Authorization", "Bearer " + user.token);
        }

        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> createMetadataCsvByAsset(String assetsJson, List<String> assetGuids) {
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>() {}.getType();
        List<Map<String, Object>> assets = new Gson().fromJson(assetsJson, type);
        Map<String, String> metadataCsvByAsset = new java.util.LinkedHashMap<>();
        if (assets == null || assets.isEmpty()) {
            return metadataCsvByAsset;
        }

        java.util.LinkedHashSet<String> headers = new java.util.LinkedHashSet<>();
        assets.forEach(asset -> headers.addAll(asset.keySet()));
        String headerLine = headers.stream().map(this::csvEscape).collect(Collectors.joining(","));

        for (Map<String, Object> asset : assets) {
            Object assetGuidValue = asset.containsKey("asset_guid") ? asset.get("asset_guid") : asset.get("assetGuid");
            if (assetGuidValue == null) {
                logger.warn("Skipping metadata CSV for asset without asset_guid: {}", asset);
                continue;
            }
            String assetGuid = String.valueOf(assetGuidValue);
            String row = headers.stream()
                    .map(header -> csvEscape(formatCsvValue(asset.get(header))))
                    .collect(Collectors.joining(","));
            metadataCsvByAsset.put(assetGuid, "sep=,\r\n" + headerLine + "\r\n" + row + "\r\n");
        }

        for (String assetGuid : assetGuids) {
            metadataCsvByAsset.putIfAbsent(assetGuid, "sep=,\r\n" + headerLine + "\r\n");
        }
        return metadataCsvByAsset;
    }

    private Map<String, String> createExternalMetadataCsvByAsset(List<String> assetGuids) throws IOException, InterruptedException {
        Map<String, String> metadataCsvByAsset = new java.util.LinkedHashMap<>();
        for (String assetGuid : assetGuids) {
            HttpResponse<String> response = fetchExternalMetadataCsv(assetGuid);
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null) {
                throw new AssetBundleCreationException(
                        "External metadata CSV not available for " + assetGuid + " (HTTP " + response.statusCode() + ")"
                );
            }
            metadataCsvByAsset.put(assetGuid, response.body());
        }
        return metadataCsvByAsset;
    }

    private HttpResponse<String> fetchExternalMetadataCsv(String assetGuid) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(assetServiceProperties.rootUrl() + "/api/extern/metadata/" + assetGuid + "/csv"))
                .header("Accept", "text/csv")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String formatCsvValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return new Gson().toJson(value);
        }
        return String.valueOf(value);
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean mustQuote = value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n");
        String escaped = value.replace("\"", "\"\"");
        return mustQuote ? "\"" + escaped + "\"" : escaped;
    }

    private void createAssetBundleZip(File zipFile, List<String> assetGuids, Map<String, String> metadataCsvByAsset, Map<String, List<DasscoFile>> filesByAsset, User user) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            Set<String> zipEntries = new HashSet<>();
            for (String assetGuid : assetGuids) {
                String assetFolder = safeZipName(assetGuid) + "/";
                addDirectoryEntry(zip, zipEntries, assetFolder);
                addMetadataCsvEntry(zip, zipEntries, assetFolder, metadataCsvByAsset.get(assetGuid));

                for (DasscoFile dasscoFile : filesByAsset.getOrDefault(assetGuid, List.of())) {
                    AssetPath assetPath = parseAssetPath(dasscoFile.path());
                    if (assetPath == null) {
                        logger.warn("Skipping file with unexpected asset path {}", dasscoFile.path());
                        continue;
                    }

                    Optional<FileService.FileResult> file = cacheFileService.tryGetFile(
                            assetPath.institution(), assetPath.collection(), assetPath.assetGuid(), assetPath.filePath(), user);
                    if (file.isPresent()) {
                        String entryName = assetFolder + safeZipPath(assetPath.filePath());
                        addFileEntry(zip, zipEntries, entryName, file.get());
                    }
                }
            }
        }
    }

    private void addMetadataCsvEntry(ZipOutputStream zip, Set<String> zipEntries, String assetFolder, String csvString) throws IOException {
        if (csvString == null) {
            return;
        }
        addFileEntry(zip, zipEntries, assetFolder + "metadata.csv", csvString);
    }

    private void addDirectoryEntry(ZipOutputStream zip, Set<String> zipEntries, String entryName) throws IOException {
        if (zipEntries.add(entryName)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.closeEntry();
        }
    }

    private void addFileEntry(ZipOutputStream zip, Set<String> zipEntries, String entryName, FileService.FileResult file) throws IOException {
        if (!zipEntries.add(entryName)) {
            logger.warn("Skipping duplicate ZIP entry {}", entryName);
            return;
        }
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream inputStream = file.is()) {
            inputStream.transferTo(zip);
        }
        zip.closeEntry();
    }

    private void addFileEntry(ZipOutputStream zip, Set<String> zipEntries, String entryName, String content) throws IOException {
        if (!zipEntries.add(entryName)) {
            logger.warn("Skipping duplicate ZIP entry {}", entryName);
            return;
        }
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private AssetPath parseAssetPath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String[] parts = normalized.split("/", 4);
        if (parts.length < 4) {
            return null;
        }
        return new AssetPath(parts[0], parts[1], parts[2], parts[3]);
    }

    private String safeZipPath(String path) {
        StringBuilder safePath = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                continue;
            }
            if (!safePath.isEmpty()) {
                safePath.append('/');
            }
            safePath.append(part);
        }
        return safePath.toString();
    }

    private String safeZipName(String name) {
        return safeZipPath(name).replace("/", "_");
    }

    private record AssetPath(String institution, String collection, String assetGuid, String filePath) {
    }

    private static class AssetBundleCreationException extends RuntimeException {
        private AssetBundleCreationException(String message) {
            super(message);
        }
    }
}
