package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.assets.AssetServiceProperties;
import dk.northtech.dasscofileproxy.domain.AssetFull;
import jakarta.inject.Inject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AssetService {

    private final KeycloakService keycloakService;
    private final AssetServiceProperties assetServiceProperties;

    @Inject
    public AssetService(KeycloakService keycloakService, AssetServiceProperties assetServiceProperties) {
        this.keycloakService = keycloakService;
        this.assetServiceProperties = assetServiceProperties;
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
