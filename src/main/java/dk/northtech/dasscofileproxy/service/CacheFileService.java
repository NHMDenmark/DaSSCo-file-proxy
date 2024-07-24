package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.assets.ErdaProperties;
import dk.northtech.dasscofileproxy.configuration.ERDADataSourceConfig;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoIllegalActionException;
import dk.northtech.dasscofileproxy.repository.FileRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.StreamingOutput;
import joptsimple.internal.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Service
public class CacheFileService {
    private static final Logger logger = LoggerFactory.getLogger(CacheFileService.class);
    private final AssetServiceProperties assetServiceProperties;
    private final FileService fileService;
    private final ShareConfig shareConfig;
    private final ErdaProperties erdaProperties;


    @Inject
    public CacheFileService(AssetServiceProperties assetServiceProperties, FileService fileService, ShareConfig shareConfig, ErdaProperties erdaProperties) {
        this.assetServiceProperties = assetServiceProperties;
        this.fileService = fileService;
        this.shareConfig = shareConfig;

        this.erdaProperties = erdaProperties;
    }

    public Optional<FileService.FileResult> getFile(String institution, String collection, String assetGuid, String filePath, User user) {
        if (!validateAccess(user, assetGuid)) {
            throw new DasscoIllegalActionException("User does not have access");
        }
        //Check cache first
        String path = Strings.join(new String[]{shareConfig.cacheFolder(), institution, collection, assetGuid, filePath}, "/");
        System.out.println(filePath);
        Optional<FileService.FileResult> file = fileService.getFile(path);
        if (file.isPresent()) {
            return file;
        }
        logger.info("File didnt exist in cache, fetching from ERDA");
        String erdaLocation = Strings.join(new String[]{erdaProperties.httpURL(), institution, collection, assetGuid, filePath}, "/");
        logger.info("ERDA location: {}", erdaLocation);
        InputStream inputStream = fetchFromERDA(erdaLocation);
        try {
            logger.info("got stream");
            new File(path).mkdirs();
            Files.copy(inputStream, Path.of(path), StandardCopyOption.REPLACE_EXISTING);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return fileService.getFile(path);
    }

    public InputStream fetchFromERDA(String path) {


        HttpClient httpClient = HttpClient.newBuilder().build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    //                .header("Authorization", "Bearer " + user.token)
                    .uri(new URI(path))
                    .GET()
                    .build();
            new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException, WebApplicationException {
                }
            };
            HttpResponse<InputStream> send = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if(send.statusCode() >199 && send.statusCode() < 300) {
                System.out.println("return body");
                return send.body();
            }
        } catch (Exception e) {
            logger.error("error",e);
            throw new RuntimeException(e);
        }
        //Caching
        return null;
    }

    public boolean validateAccess(User user, String assetGuid) {
        // Retrieve service token
//        var token = this.keycloakService.getAdminToken();

        try (HttpClient httpClient = HttpClient.newBuilder().build();) {
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + user.token).uri(new URI(this.assetServiceProperties.rootUrl() + "/api/v1/assets/readaccess?assetGuid=" + assetGuid))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (send.statusCode() > 199 && send.statusCode() < 300) {
                return true;
            }
            if (send.statusCode() == 404) {
                throw new FileNotFoundException("Asset did not exist");
            }
//            if(send.statusCode() == 401 ||send.statusCode() == 403) {
            throw new DasscoIllegalActionException("User does not have access to asset");
//            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to check user access to asset", e);
        }
    }
}
