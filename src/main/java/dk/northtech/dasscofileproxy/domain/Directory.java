package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

import java.io.File;
import java.time.Instant;
import java.util.List;

public record Directory(
        @Schema(description = "ID for the Directory", example = "")
        Long directoryId,
        @Schema(description = "Uniform Resource Identifier for the directory.", example = "")
        String uri,
        @Schema(description = "", example = "")
        String node_host,
        @Schema(description = "Privilege required to perform an action", example = "WRITE")
        AccessType access,
        @Schema(description = "Date and time of creation", example = "2023-05-24T00:00:00.000Z")
        Instant creationDatetime,
        @Schema(description = "Allocation in memory for the directory, in MB", example = "10")
        int allocatedStorageMb,
        @Schema(description = "Shows if the directory is awaiting synchronizing with ERDA", example = "false")
        boolean awaitingErdaSync,
        @Schema(description = "Number of attempts to synchronize with ERDA", example = "0")
        int erdaSyncAttempts,
        @Schema(description = "User attempting the synchronization", example = "THBO")
        String syncUser,
        @Schema(description = "Workstation attempting the synchronization", example = "ti-ws-01")
        String syncWorkstation,
        @Schema(description = "Pipeline attempting the synchronization", example = "ti-p1")
        String syncPipeline,
        @Schema(description = "List of assets shared", example = "")
        @Nullable List<SharedAsset> sharedAssets,
        @Schema(description = "List of users that accessed the directory", example = "") // I think.
        @Nullable List<UserAccess> userAccess) {

    @JdbiConstructor
    public Directory {
    }

    public Directory(Long directoryId, String uri, String node_host, AccessType access, Instant creationDatetime, int allocatedStorageMb, boolean awaitingErdaSync, int erdaSyncAttempts, List<SharedAsset> sharedAssets, List<UserAccess> userAccess) {
        this(directoryId, uri, node_host, access, creationDatetime, allocatedStorageMb, awaitingErdaSync, erdaSyncAttempts, null, null, null, sharedAssets, userAccess);
    }

    public Directory(Directory directory, Long directoryId) {
        this(directoryId
                , directory.uri
                , directory.node_host
                , directory.access()
                , directory.creationDatetime
                , directory.allocatedStorageMb
                , directory.awaitingErdaSync
                , directory.erdaSyncAttempts
                , directory.syncUser
                , directory.syncWorkstation
                , directory.syncPipeline
                , directory.sharedAssets
                , directory.userAccess);
    }
}
