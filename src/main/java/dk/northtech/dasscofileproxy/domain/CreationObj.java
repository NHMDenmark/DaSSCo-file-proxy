package dk.northtech.dasscofileproxy.domain;

import java.util.List;

public record CreationObj(List<MinimalAsset> assets, List<String> users) {
}
