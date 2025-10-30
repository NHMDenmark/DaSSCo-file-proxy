package dk.northtech.dasscofileproxy.domain;

import com.google.gson.annotations.Expose;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record AssetChange(
        @Schema(description = "Id for the change in the database", example = "1")
        Long asset_change_id,
        @Schema(description = "Change that happen", example = "file_added")
        @Expose String change,
        @Schema(description = "Id of the person that made the change", example = "1")
        @Expose Long dassco_user_id,
        @Schema(description = "Id of the share the change happen in", example = "1")
        @Expose Long directory_id,
        @Schema(description = "The Global Unique Identifier generated for each asset", example = "ti-a01-202305241657")
        @Expose String asset_guid,
        @Schema(description = "Date and time when the change was made", example = "2023-05-24T00:00:00.000Z")
        Instant timestamp
){
}
