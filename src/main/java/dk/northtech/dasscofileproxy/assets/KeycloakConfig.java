package dk.northtech.dasscofileproxy.assets;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("keycloak")
public record KeycloakConfig(String keycloakUrl, String realm, String clientId, String clientSecret, String username, String password) {
}
