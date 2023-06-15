package dk.northtech.dasscofileproxy.assets;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("keycloak.admin")
public record KeycloakAdminConfig(String keycloakUrl, String adminRealm, String climbalongRealm, String clientId, String clientSecret, String username, String password) {
}
