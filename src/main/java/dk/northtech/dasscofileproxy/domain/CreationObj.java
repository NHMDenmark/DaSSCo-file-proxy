package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record CreationObj(
        @Schema(description = "", example = "")
        List<MinimalAsset> assets,
        @Schema(description = "List of users", example = "")
        List<String> users,
        @Schema(description = "Allocation in memory for the asset, in MB", example = "10")
        int allocation_mb) {
}
