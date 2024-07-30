package dk.northtech.dasscofileproxy.webapi.model;

import com.google.common.base.Strings;

public record FileUploadData(String asset_guid, String institution, String collection, String filePathAndName, int size_mb)  {
    public String getAssetFilePath() {
        String lastPart = "/";
        if(filePathAndName != null) {
            lastPart = (filePathAndName.startsWith("/") ? filePathAndName : "/" + filePathAndName);
        }
        return "/assetfiles/" + institution + "/" + collection + "/" + asset_guid + lastPart;
    }
    public String getPath() {
        String lastPart = "/";
        if(filePathAndName != null) {
            lastPart = (filePathAndName.startsWith("/") ? filePathAndName : "/" + filePathAndName);
        }
        return "/" + institution + "/" + collection + "/" + asset_guid + lastPart;
    }
    public String getBasePath() {
        return "/assetfiles/" + institution + "/" + collection + "/" + asset_guid + "/";
    }

    public void validate() {
        if(Strings.isNullOrEmpty(asset_guid)) {
            throw new IllegalArgumentException("assetGuid is missing");
        }
        if(Strings.isNullOrEmpty(institution)) {
            throw new IllegalArgumentException("asset must have an institution");
        }
        if(Strings.isNullOrEmpty(collection)) {
            throw new IllegalArgumentException("asset must be in a collection");
        }
    }
}
