package dk.northtech.dasscofileproxy.domain;

public record KeycloakToken (String accessToken, long expiresIn, long refreshExpiresIn, String tokenType, String scope) {


}