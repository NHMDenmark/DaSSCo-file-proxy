package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashSet;
import java.util.Set;

public record MinimalAsset(
        @Schema(description = "The Global Unique Identifier generated for each asset", example = "ti-a01-202305241657")
        String asset_guid,
        @Schema(description = "Name of the parent media (in most cases, the same as original_parent_name, it can be different if it is a derivative of a derivative)", example = "ti-a02-202305241657")
        Set<String> parent_guids,
        @Schema(description = "The name of the institution which owns and digitised the specimen", example = "NNAD")
        String institution,
        @Schema(description = "The collection name within the institution that holds the specimen", example = "test-collection")
        String collection) {

        @Override
        public Set<String> parent_guids() {
                return parent_guids == null ? new HashSet<>(): parent_guids;
        }
}
