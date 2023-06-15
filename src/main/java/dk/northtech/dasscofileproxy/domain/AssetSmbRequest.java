package dk.northtech.dasscofileproxy.domain;

import jakarta.annotation.Nullable;

public record AssetSmbRequest(String shareName, @Nullable String assetId) {

}
