package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public record AssetCache (
    Long assetCacheId,
    @Schema(description = "Path to the Asset", example = "assetfiles/test-institution/test-collection/nt_asset_19/")
    String assetPath,
    @Schema(description = "Size of the file", example = "1")
    long fileSize,
    @Schema(description = "Date and time of expiration", example = "2007-12-03T10:15:30")
    LocalDateTime expirationDatetime,
    @Schema(description = "Date and time of creation", example = "2007-12-03T10:15:30")
    LocalDateTime creationDatetime

){}
