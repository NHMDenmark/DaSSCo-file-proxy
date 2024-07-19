package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.configuration.ShareConfig;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoIllegalActionException;
import jakarta.inject.Inject;
import joptsimple.internal.Strings;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

@Service
public class CacheFileService {

    private final AssetServiceProperties assetServiceProperties;
    private final FileService fileService;
    private final ShareConfig shareConfig;
    @Inject
    public CacheFileService(AssetServiceProperties assetServiceProperties, FileService fileService, ShareConfig shareConfig) {
        this.assetServiceProperties = assetServiceProperties;
        this.fileService = fileService;
        this.shareConfig = shareConfig;
    }

    public Optional<FileService.FileResult> getFile(String institution, String collection, String assetGuid, String filePath, User user) {
        if(!validateAccess(user)) {
            throw new DasscoIllegalActionException("User does not have access");
        }
        //Check cache first
        String path = Strings.join(new String[]{shareConfig.cacheFolder(), institution, collection, assetGuid, filePath}, "/");
        System.out.println(filePath);
        Optional<FileService.FileResult> file = fileService.getFile(path);
        if(file.isPresent()){
            return file;
        }
        //Fetch from ERDA

        //Populate cache

    }

    public boolean validateAccess(User user) {
        // Retrieve service token
//        var token = this.keycloakService.getAdminToken();
        HttpRequest request = null;
        try (HttpClient httpClient = HttpClient.newBuilder().build();){
            request = HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + user.token).uri(new URI(this.assetServiceProperties.rootUrl() + "/api/v1/assets/readaccess"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = send.body();
            if (send.statusCode() > 199 && send.statusCode() < 300) {
                return true;
            }
            if(send.statusCode() == 404) {
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
