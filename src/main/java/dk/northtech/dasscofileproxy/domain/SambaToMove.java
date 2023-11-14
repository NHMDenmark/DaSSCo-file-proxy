package dk.northtech.dasscofileproxy.domain;

public class SambaToMove {
    public SambaToMove(SambaServer sambaServer, AssetUpdateRequest assetUpdateRequest) {
        this.sambaServer = sambaServer;
        this.assetUpdateRequest = assetUpdateRequest;
    }

    public SambaServer sambaServer;
    public AssetUpdateRequest assetUpdateRequest;
}
