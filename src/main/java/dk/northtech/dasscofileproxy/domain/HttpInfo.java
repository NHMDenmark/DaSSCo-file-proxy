package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

public record HttpInfo(
        @Schema(description = "Path to the asset", example = "/assetfiles/<institution>/<collection>/<asset_guid>/")
        String path,
        @Schema(description = "Name of the host where the asset is uploaded", example = "https://storage.test.dassco.dk/file_proxy/api")
        String hostname,
        @Schema(description = "The total storage of the server where the FileProxy is deployed", example = "90000")
        int total_storage_mb,
        @Schema(description = "The total amount of storage dedicated for “caching” files for external linking and other use", example = "20000")
        int cache_storage_mb,
        @Schema(description = "The total amount of storage allocated", example = "20000")
        int all_allocated_storage_mb,
        @Schema(description = "The remaining storage on the server: total - cache - all_allocated = remaining", example = "60000")
        int remaining_storage_mb,
        @Schema(description = "The amount of storage allocated on the server to the new asset", example = "5000")
        int allocated_storage_mb,
        @Schema(description = "A detailed error message if an error happens", example = "Allocation failed, no more disk space")
        String proxy_allocation_status_text,
        @Schema(description = "Status of the allocation", example = "SUCCESS")
        HttpAllocationStatus http_allocation_status,
        @Schema(description = "Asset's parent size (in mb)", example = "20")
        long parent_size_mb) {
    public HttpInfo {
    }

    public HttpInfo(String proxy_allocation_status_text, HttpAllocationStatus httpAllocationStatus) {
        this(null, null, 0, 0, 0, 0,0, proxy_allocation_status_text, httpAllocationStatus, 0);
    }
}
