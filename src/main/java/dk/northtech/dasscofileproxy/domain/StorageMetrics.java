package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

public record StorageMetrics(
        @Schema(description = "The total storage of the server where the FileProxy is deployed", example = "90000")
        int total_storage_mb,
        @Schema(description = "The total amount of storage dedicated for “caching” files for external linking and other use", example = "20000")
        int cache_storage_mb,
        @Schema(description = "The total amount of storage allocated", example = "20000")
        int all_allocated_storage_mb,
        @Schema(description = "The remaining storage on the server: total - cache - all_allocated = remaining", example = "60000")
        int remaining_storage_mb) {
    //negative amounts are subtracted from totalAllocation, added to remaining storage
    public StorageMetrics allocate(int allocationChange) {
        return new StorageMetrics(total_storage_mb, cache_storage_mb, all_allocated_storage_mb + allocationChange, remaining_storage_mb - allocationChange);
    }
}
