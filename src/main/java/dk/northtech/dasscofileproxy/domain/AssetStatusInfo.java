package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record AssetStatusInfo(
        @Schema(description = "The Global Unique Identifier generated for each asset", example = "ti-a01-202305241657")
        String asset_guid,
        @Schema(description = "Name of the parent media (in most cases, the same as original_parent_name, it can be different if it is a derivative of a derivative)", example = "ti-a02-202305241657")
        String parent_guid,
        @Schema(description = "Date and time that the error happened", example = "2023-05-24T00:00:00.000Z")
        Instant error_timestamp,
        @Schema(description = "The current status of an asset", example = "BEING_PROCESSED")
        InternalStatus status,
        @Schema(description = "If an error happened during digitisation of the asset an error message can be displayed here", example = "Failed to upload to ERDA: connection reset")
        String error_message,
        @Schema(description = "Number of MB allocated for the asset", example = "10")
        Integer share_allocation_mb) {

    public AssetStatusInfo(String asset_guid, String parent_guid, Instant error_timestamp, InternalStatus status, String error_message) {
        this(asset_guid, parent_guid, error_timestamp, status, error_message, null);
    }
}
