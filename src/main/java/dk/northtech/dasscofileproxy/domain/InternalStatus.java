package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An internal status field used to track the status of the upload of related media", example = "COMPLETED")
public enum InternalStatus {
    METADATA_RECEIVED
    , ASSET_RECEIVED
    , COMPLETED
    , ERDA_FAILED
}
