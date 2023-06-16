package dk.northtech.dasscofileproxy.domain;

import java.time.Instant;

public record KeycloakToken (String accessToken, long expiresIn, Instant accessExpirationTimeStamp, long refreshExpiresIn, Instant refreshExpirationTimeStamp, String tokenType, String refreshToken, String scope) {


}
