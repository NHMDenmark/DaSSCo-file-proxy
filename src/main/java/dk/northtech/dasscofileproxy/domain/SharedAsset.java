package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record SharedAsset (
        @Schema(description = "Id for the shared asset", example = "")
        Long sharedAssetId,
        @Schema(description = "ID for the directory", example = "")
        Long directoryId,
        @Schema(description = "The Global Unique Identifier generated for each asset", example = "ti-a01-202305241657")
        String assetGuid,
        @Schema(description = "Date and time when the shared asset was created", example = "2023-05-24T00:00:00.000Z")
        Instant creationDatetime) {

}
