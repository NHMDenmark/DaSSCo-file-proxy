package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record AssetStatusInfo(
        String asset_guid,

        String parent_guid,

        Instant error_timestamp,

        InternalStatus status,

        String error_message,

        Integer share_allocation_mb) {

    public AssetStatusInfo(String asset_guid, String parent_guid, Instant error_timestamp, InternalStatus status, String error_message) {
        this(asset_guid, parent_guid, error_timestamp, status, error_message, null);
    }
}
