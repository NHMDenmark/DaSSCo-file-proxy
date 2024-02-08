package dk.northtech.dasscofileproxy.domain;

public record StorageMetrics(int total_storage_mb, int cache_storage_mb, int all_allocated_storage_mb, int remaining_storage_mb) {
    //negative amounts are subtracted from totalAllocation, added to remaining storage
    public StorageMetrics allocate(int allocationChange) {
        return new StorageMetrics(total_storage_mb, cache_storage_mb, all_allocated_storage_mb + allocationChange, remaining_storage_mb - allocationChange);
    }
}
