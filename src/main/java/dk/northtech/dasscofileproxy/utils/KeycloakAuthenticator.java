package dk.northtech.dasscofileproxy.utils;

import dk.northtech.dasscofileproxy.assets.KeycloakConfig;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

@Service
public class KeycloakAuthenticator extends Authenticator {
    KeycloakConfig keycloakConfig;

    @Inject
    public KeycloakAuthenticator(KeycloakConfig keycloakConfig) {
        this.keycloakConfig = keycloakConfig;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(
                keycloakConfig.username(),
                keycloakConfig.password().toCharArray());
    }
}
