package dk.northtech.dasscofileproxy.domain;

public record StorageMetrics(int total_storage_mb, int cache_storage_mb, int all_allocated_storage_mb, int remaining_storage_mb) {

}
