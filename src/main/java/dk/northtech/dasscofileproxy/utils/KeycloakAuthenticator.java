package dk.northtech.dasscofileproxy.utils;

import dk.northtech.dasscofileproxy.assets.KeycloakAdminConfig;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

@Service
public class KeycloakAuthenticator extends Authenticator {
    KeycloakAdminConfig keycloakAdminConfig;

    @Inject
    public KeycloakAuthenticator(KeycloakAdminConfig keycloakAdminConfig) {
        this.keycloakAdminConfig = keycloakAdminConfig;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(
                keycloakAdminConfig.username(),
                keycloakAdminConfig.password().toCharArray());
    }
}
