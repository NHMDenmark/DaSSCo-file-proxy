package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.User;

import java.io.File;
import java.util.List;

@FunctionalInterface
public interface ExternalAssetBundleCreator {
    File createExternal(List<String> assetGuids, User user) throws Exception;
}
