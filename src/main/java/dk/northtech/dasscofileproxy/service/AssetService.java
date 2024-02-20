package dk.northtech.dasscofileproxy.service;

import com.nimbusds.jose.shaded.gson.Gson;
import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.domain.AssetFull;
import dk.northtech.dasscofileproxy.domain.AssetUpdateRequest;
import dk.northtech.dasscofileproxy.domain.InternalStatus;
import dk.northtech.dasscofileproxy.domain.MinimalAsset;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Service
public class AssetService {
    private static final Logger logger = LoggerFactory.getLogger(AssetService.class);
    private final KeycloakService keycloakService;
    private final AssetServiceProperties assetServiceProperties;

    @Inject
    public AssetService(KeycloakService keycloakService, AssetServiceProperties assetServiceProperties) {
        this.keycloakService = keycloakService;
        this.assetServiceProperties = assetServiceProperties;
    }

    public void setAssestStatus(String assetGuid, InternalStatus status, @Nullable String errorMessage) {
        var token = this.keycloakService.getAdminToken();
        try {
            URIBuilder urlWithParams = new URIBuilder(assetServiceProperties.rootUrl() + "/api/v1/assetmetadata/" + assetGuid + "/setstatus")
                    .addParameter("newStatus", status.name());
            if(errorMessage != null) {
                urlWithParams.addParameter("errorMessage", errorMessage);
            }
//                    .build()
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .uri(urlWithParams.build())
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpClient httpClient = HttpClient.newBuilder().build();
            HttpResponse httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!(httpResponse.statusCode() > 199 && httpResponse.statusCode() < 300)){
                logger.warn("Failed to set status, request failed with status code: " + httpResponse.statusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to set failed status on asset :^(");
            throw new RuntimeException(e);
        }
    }

    public List<MinimalAsset> getInProgressAssets(boolean onlyFailed) {
        var token = this.keycloakService.getAdminToken();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .uri(new URIBuilder(assetServiceProperties.rootUrl() + "/api/v1/assets/inprogress")
                            .addParameter("onlyFailed", onlyFailed +"")
                            .build())
                    .GET()
                    .build();
            HttpClient httpClient = HttpClient.newBuilder().build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!(httpResponse.statusCode() > 199 && httpResponse.statusCode() < 300)){
                logger.warn("Failed to set status, request failed with status code: " + httpResponse.statusCode());
            }
            String body = httpResponse.body();
            Gson gson = new Gson();
            return null;
        } catch (Exception e) {
            logger.error("Failed to set failed status on asset :^(");
            throw new RuntimeException(e);
        }
    }

    public boolean completeAsset(AssetUpdateRequest updateRequest) {
        var token = this.keycloakService.getAdminToken();
        try {
            Gson gson = new Gson();
            String postbody = gson.toJson(updateRequest);
            String guid = updateRequest.minimalAsset().asset_guid();
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .uri(new URIBuilder(assetServiceProperties.rootUrl() + "/api/v1/assetmetadata/" + guid + "/complete")
                            .build())
                    .POST(HttpRequest.BodyPublishers.ofString(postbody))
                    .build();
            HttpClient httpClient = HttpClient.newBuilder().build();
            HttpResponse httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!(httpResponse.statusCode() > 199 && httpResponse.statusCode() < 300)) {
                logger.warn("Failed to complete asset, request failed with status code: " + httpResponse.statusCode());
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to set failed status on asset :^(");
            throw new RuntimeException(e);
        }
    }

    public AssetFull getFullAsset(String guid) {
        // Retrieve service token
        var token = this.keycloakService.getAdminToken();

        // Create RestTemplate instance
        RestTemplate restTemplate = new RestTemplate();

        // Set the authorization header
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);

        // Create the HTTP entity with headers
        HttpEntity<String> entity = new HttpEntity<>(headers);

        // Make the GET request
        String url = assetServiceProperties.rootUrl() + "/api/v1/assetmetadata/" + guid;
        ResponseEntity<AssetFull> response = restTemplate.exchange(url, HttpMethod.GET, entity, AssetFull.class);

        // Return the response body
        return response.getBody();
    }
}
