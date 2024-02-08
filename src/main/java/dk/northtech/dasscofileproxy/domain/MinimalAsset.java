package dk.northtech.dasscofileproxy.domain;

import com.google.common.base.Strings;
import jakarta.annotation.Nullable;

public record MinimalAsset(String asset_guid, String parentGuid, String institution, String collection) {

}
