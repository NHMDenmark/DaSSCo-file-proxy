package dk.northtech.dasscofileproxy.domain;

import java.time.Instant;

public record SharedAsset (Long sharedAssetId, Long sambaServerId, String assetGuid, Instant creationDatetime) {

}
