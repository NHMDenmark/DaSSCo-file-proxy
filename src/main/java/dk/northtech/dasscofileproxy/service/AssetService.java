package dk.northtech.dasscofileproxy.service;

import com.nimbusds.jose.shaded.gson.Gson;
import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.domain.AssetFull;
import dk.northtech.dasscofileproxy.domain.AssetUpdateRequest;
import dk.northtech.dasscofileproxy.domain.InternalStatus;
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

    public void setFailedStatus(String assetGuid, InternalStatus err_status) {
        var token = this.keycloakService.getAdminToken();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .uri(new URIBuilder(assetServiceProperties.rootUrl() + "/api/v1/assetmetadata/" + assetGuid + "/seterrorstatus")
                            .addParameter("newStatus", err_status.name())
                            .build())
                    .POST(HttpRequest.BodyPublishers.noBody())
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

    public void completeAsset(AssetUpdateRequest updateRequest) {
        var token = this.keycloakService.getAdminToken();
        try {
            Gson gson = new Gson();
            String postbody = gson.toJson(updateRequest);
            String guid = updateRequest.minimalAsset().asset_guid();
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .uri(new URIBuilder(assetServiceProperties.rootUrl() + "/api/v1/assetmetadata/" + guid + "/complete")
                            .build())
                    .POST(HttpRequest.BodyPublishers.ofString(postbody))
                    .build();
            HttpClient httpClient = HttpClient.newBuilder().build();
            HttpResponse httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!(httpResponse.statusCode() > 199 && httpResponse.statusCode() < 300)) {
                logger.warn("Failed to complete asset, request failed with status code: " + httpResponse.statusCode());
            }
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
