package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record KeycloakToken (
        @Schema(description = "Token delivered by the server that allows the application to access the resource", example = "")
        String accessToken,
        @Schema(description = "Time for the token to expire", example = "")
        long expiresIn,
        @Schema(description = "Date and time when the token expires", example = "2023-05-24T00:00:00.000Z")
        Instant accessExpirationTimeStamp,
        @Schema(description = "Time for the refresh token to expire", example = "")
        long refreshExpiresIn,
        @Schema(description = "Date and time when the refresh token expires", example = "2023-05-24T00:00:00.000Z")
        Instant refreshExpirationTimeStamp,
        @Schema(description = "Type of the token", example = "")
        String tokenType,
        @Schema(description = "Refresh token", example = "")
        String refreshToken,
        @Schema(description = "Scope of the token", example = "")
        String scope) {

}
