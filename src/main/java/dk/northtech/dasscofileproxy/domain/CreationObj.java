package dk.northtech.dasscofileproxy.domain;

import java.util.List;

public record CreationObj(List<String> assetGuids, List<String> users) {
}
