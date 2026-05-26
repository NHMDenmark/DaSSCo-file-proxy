package dk.northtech.dasscofileproxy.domain;


public class SyncParkingSpaceRequest {
    public MinimalAsset asset;
    public Long specifySyncLogId;
    public String attachmentLocation;

    public SyncParkingSpaceRequest(MinimalAsset asset, Long specifySyncLogId, String attachmentLocation) {
        this.asset = asset;
        this.specifySyncLogId = specifySyncLogId;
        this.attachmentLocation = attachmentLocation;
    }
}
