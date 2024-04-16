package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Sync status of the file", example = "SYNCHRONIZED")
public enum FileSyncStatus {
    NEW_FILE,
    SYNCHRONIZED
}
