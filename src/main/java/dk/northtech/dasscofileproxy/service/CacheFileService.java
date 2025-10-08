package dk.northtech.dasscofileproxy.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.net.UrlEscapers;
import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.assets.ErdaProperties;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.CacheInfo;
import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.domain.FileSyncStatus;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoIllegalActionException;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoNotFoundException;
import dk.northtech.dasscofileproxy.repository.FileCacheRepository;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import joptsimple.internal.Strings;
import org.apache.commons.io.IOUtils;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CacheFileService {
    private static final Logger logger = LoggerFactory.getLogger(CacheFileService.class);
    private final AssetServiceProperties assetServiceProperties;
    private final FileService fileService;
    private final ShareConfig shareConfig;
    private final ErdaProperties erdaProperties;
    private final Jdbi jdbi;

    private Set<Long> idsToRefresh = new HashSet<>();

    @Inject
    public CacheFileService(AssetServiceProperties assetServiceProperties, FileService fileService, ShareConfig shareConfig, ErdaProperties erdaProperties, Jdbi jdbi) {
        this.assetServiceProperties = assetServiceProperties;
        this.fileService = fileService;
        this.shareConfig = shareConfig;
        this.erdaProperties = erdaProperties;
        this.jdbi = jdbi;
        cachedFiles = Caffeine.newBuilder()
                .maximumSize(100000)
                .build(x -> jdbi.withHandle(h -> {
                    FileCacheRepository attach = h.attach(FileCacheRepository.class);
                    return attach.getFileCacheByPath(x).orElse(null);
                }));
    }

    LoadingCache<String, CacheInfo> cachedFiles;

    public Optional<FileService.FileResult> getFile(String institution, String collection, String assetGuid, String filePath, User user) {
        logger.info("validating access");
        if (!validateAccess(user, assetGuid)) {
            throw new DasscoIllegalActionException("User does not have access");
        }
        logger.info("finished validating access");
        //Check cache first
        String path = Strings.join(new String[]{shareConfig.cacheFolder(), institution, collection, assetGuid, filePath}, "/");
        try {
            String assetPath = "/" + Strings.join(new String[]{institution, collection, assetGuid, filePath}, "/");
            CacheInfo cacheInfo = cachedFiles.get(assetPath);
            if (cacheInfo != null) {
                Optional<FileService.FileResult> file = fileService.getFile(path);
                // If the file does not exist, another node has likely deleted it.
                if(file.isPresent()){
                    idsToRefresh.add(cacheInfo.fileCacheId());
                    return file;
                }
                cachedFiles.invalidate(cacheInfo.path());
            }
            FileRepository fileRepository = jdbi.onDemand(FileRepository.class);
            DasscoFile filesByAssetPath = fileRepository.getFilesByAssetPath(assetPath);
            if (filesByAssetPath == null) {
                throw new DasscoNotFoundException("The asset does not have this file");
            }
            if (filesByAssetPath.syncStatus() != FileSyncStatus.SYNCHRONIZED || filesByAssetPath.deleteAfterSync()) {
                throw new DasscoIllegalActionException("File is being edited");
            }
            logger.info("File didnt exist in cache, fetching from ERDA");
            String erdaLocation = UrlEscapers.urlFragmentEscaper().escape(Strings.join(new String[]{erdaProperties.httpURL(), institution, collection, assetGuid, filePath}, "/"));
//            erdaLocation = UriUtils.encodePath(erdaLocation, "UTF-8");
            try (InputStream inputStream = fetchFromERDA(erdaLocation)) {
                logger.info("got stream");
                new File(path).mkdirs();
                Files.copy(inputStream, Path.of(path), StandardCopyOption.REPLACE_EXISTING);
            }
            cacheFile(filesByAssetPath);
            return fileService.getFile(path);
        } catch (Exception e) {
            logger.error("error", e);
            throw new RuntimeException(e);
        }
    }

    public Response streamFile(String institution, String collection, String assetGuid, String filePath, User user, boolean inline){
        logger.info("validating access");
        if (!validateAccess(user, assetGuid)) {
            throw new DasscoIllegalActionException("User does not have access");
        }
        logger.info("finished validating access");

        String assetPath = "/" + Strings.join(new String[]{institution, collection, assetGuid, filePath}, "/");
        FileRepository fileRepository = jdbi.onDemand(FileRepository.class);
        DasscoFile filesByAssetPath = fileRepository.getFilesByAssetPath(assetPath);
        if (filesByAssetPath == null) {
            throw new DasscoNotFoundException("The asset does not have this file");
        }
        if (filesByAssetPath.syncStatus() != FileSyncStatus.SYNCHRONIZED || filesByAssetPath.deleteAfterSync()) {
            throw new DasscoIllegalActionException("File is being edited");
        }
        String erdaLocation = UrlEscapers.urlFragmentEscaper().escape(Strings.join(new String[]{erdaProperties.httpURL(), institution, collection, assetGuid, filePath}, "/"));

        try {
            StreamingOutput streamingOutput = output -> {
                try (BufferedInputStream bufferedInputStream = new BufferedInputStream(fetchFromERDA(erdaLocation))) {
                    IOUtils.copy(bufferedInputStream, output);
                    output.flush();
                } catch (IOException e) {
                    throw new RuntimeException("Error while transferring data", e);
                }
            };

            return Response.ok(streamingOutput)
                    .header("Content-Disposition", inline ? "inline; attachment; filename=" + filePath + "/" : "attachment; filename=" + filePath + "/")
                    .header("Content-Type", "image/png")
                    .build();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void cacheFile(DasscoFile dasscoFile) {
        FileCacheRepository fileCacheRepository = jdbi.onDemand(FileCacheRepository.class);
        CacheInfo cacheInfo = new CacheInfo(dasscoFile.path(), Instant.now().plus(1, ChronoUnit.DAYS), dasscoFile.fileId());
        fileCacheRepository.insertCache(cacheInfo);
        Optional<CacheInfo> fileCacheByPath = fileCacheRepository.getFileCacheByPath(dasscoFile.path());
        cachedFiles.put(dasscoFile.path(), fileCacheByPath.orElseThrow(() -> new RuntimeException("Some thing went wrong :^(")));
    }

    // WARNING WARNING WARNING
    // If we are going to run in more than one instance we should create a lock in the db so only one instance runs the eviction code.
    // We can use SELECT FOR UPDATE.
    @Scheduled(cron = "0 15,45 * * * *") // at min 15 and 45
    public void removedExpiredCaches() {
        jdbi.inTransaction(h -> {
        logger.info("Running cache eviction code");
                FileCacheRepository fileCacheRepository = h.attach(FileCacheRepository.class);
                // If more than 90% of available space is used, we evict based on disk usage as well as expiration date.
                long maxBytes = (long) (.90 * (shareConfig.cacheDiskspace() * 1000000L));
                List<String> pathsToDelete = fileCacheRepository.evict(maxBytes);
                for (String s : pathsToDelete) {
                    this.cachedFiles.invalidate(s);
                    String locationOnDisk = shareConfig.cacheFolder() + s;
                    logger.info("Evicting {}", locationOnDisk);
                    boolean b = fileService.deleteFile(locationOnDisk);
                }

            return pathsToDelete;
        });
    }

    @Scheduled(cron = "0 0,30 * * * *") // at min 0 and 30
    public void refreshCache() {
        logger.info("Running cache refresh code");
        if(idsToRefresh.isEmpty()) {
            return;
        }
        Instant newExpirationDate = Instant.now().plus(1, ChronoUnit.HOURS);
        List<Long> idsToUpdate;
        synchronized (this) {
            idsToUpdate = new ArrayList<>(idsToRefresh);
            idsToRefresh.clear();
        }
        logger.info("Refreshing {} cache entries", idsToUpdate.size());
        List<Long> batch = new ArrayList<>();
        FileCacheRepository fcr = jdbi.onDemand(FileCacheRepository.class);

        for(Long l: idsToUpdate) {
            batch.add(l);
            if(batch.size() > 1000) {
                fcr.refreshCacheEntries(batch, newExpirationDate);
                batch.clear();
            }
        }
        if(!batch.isEmpty()){
            fcr.refreshCacheEntries(batch, newExpirationDate);
        }
    }

    public InputStream fetchFromERDA(String erdaUrl) {
        HttpClient httpClient = HttpClient.newBuilder().build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    //                .header("Authorization", "Bearer " + user.token)
                    .uri(new URI(erdaUrl))
                    .GET()
                    .build();
            new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException, WebApplicationException {
                }
            };
            HttpResponse<InputStream> send = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (send.statusCode() > 199 && send.statusCode() < 300) {
                return send.body();
            }
        } catch (Exception e) {
            logger.error("error", e);
            throw new RuntimeException(e);
        }
        //Caching
        return null;
    }

    public boolean validateAccess(User user, String assetGuid) {
        // Retrieve service token
//        var token = this.keycloakService.getAdminToken();

        try (HttpClient httpClient = HttpClient.newBuilder().build();) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .uri(new URI(this.assetServiceProperties.rootUrl() + "/api/v1/assets/readaccess?assetGuid=" + assetGuid));
            if(user.token != null) {
                requestBuilder.header("Authorization", "Bearer " + user.token);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (send.statusCode() > 199 && send.statusCode() < 300) {
                return true;
            }
            if (send.statusCode() == 401 || send.statusCode() == 403) {
                return false;
            }
            if (send.statusCode() == 404) {
                throw new DasscoNotFoundException("Asset " + assetGuid + " does not exist");
            } else {
                throw new RuntimeException("Error occurred when querying user access to asset, response code: " + send.statusCode());
            }
        } catch (IOException | URISyntaxException | InterruptedException e) {
            System.out.println(e.getClass());
            throw new RuntimeException("Failed to check user access to asset", e);
        }
    }

    public void saveFilesTempFolder(List<String> paths, User user, String guid){
        String basePath = shareConfig.mountFolder();
        Path tempDir = Paths.get(basePath, "temp", guid);

        for (String path : paths) {
            String[] parts = path.split("/");
            String folderName = parts[parts.length - 2];
            String fileName = parts[parts.length - 1];
            String institution = parts[1];
            String collection = parts[2];
            String assetGuid = parts[3];
            String filePath = String.join(" ", Arrays.copyOfRange(parts, 4, parts.length));
            Path outputDir = tempDir.resolve(folderName);

            Optional<FileService.FileResult> file = this.getFile(institution, collection, assetGuid, filePath, user);
            file.ifPresent(f -> {
                Path outputPath = outputDir.resolve(fileName);
                try {
                    Files.copy(f.is(), outputPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }


}
