package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

public record AssetUpdateRequest(
        @Schema(description = "", example = "")
        @Nullable MinimalAsset minimalAsset,
        @Schema(description = "The name of the workstation used to do the imaging", example = "ti-ws1")
        @Nullable String workstation,
        @Schema(description = "The name of the pipeline that sent a create, update or delete request to the storage service", example = "ti-p1")
        @Nullable String pipeline,
        @Schema(description = "The name of the person who imaged the specimens (creating the assets)", example = "THBO")
        @Nullable String digitizer,
        @Schema(description = "The asset_guid for the sync event", example = "asdhjg-asdf-asdf")
        @Nullable String asset_guid,
        @Schema(description = "The directory_id for the sync event", example = "1")
        @Nullable Long directory_id
) {

}
