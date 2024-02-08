package dk.northtech.dasscofileproxy.domain;

import org.junit.jupiter.api.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class StorageMetricsTest {

    @Test
    void allocate() {
        StorageMetrics storageMetrics = new StorageMetrics(1000, 200, 600, 200);
        StorageMetrics allocate = storageMetrics.allocate(100);
        assertThat(allocate.all_allocated_storage_mb()).isEqualTo(700);
        assertThat(allocate.remaining_storage_mb()).isEqualTo(100);
    }

    @Test
    void allocate2() {
        StorageMetrics storageMetrics = new StorageMetrics(1000, 200, 600, 200);
        StorageMetrics allocate = storageMetrics.allocate(-100);
        assertThat(allocate.all_allocated_storage_mb()).isEqualTo(500);
        assertThat(allocate.remaining_storage_mb()).isEqualTo(300);
    }
}