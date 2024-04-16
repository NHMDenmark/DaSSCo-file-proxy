package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record UserAccess (
        @Schema(description = "", example = "")
        Long userAccessId,
        @Schema(description = "Id for the directory", example = "")
        Long directoryId,
        @Schema(description = "Username for the user", example = "THBO")
        String username,
        @Schema(description = "", example = "")
        String token,
        @Schema(description = "Date and time of the access", example = "2023-05-24T00:00:00.000Z")
        Instant creationDatetime) {
}
