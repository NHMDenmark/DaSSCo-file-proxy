package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.StorageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public class ErdaDataSource2 {
    private final StorageConfig storageConfig;
    private static final Logger logger = LoggerFactory.getLogger(ErdaDataSource2.class);
    Instant lastFailure = null;
    private ERDAClient theClient = null;

    public ErdaDataSource2(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }

    public ERDAClient getClient() {
        try {
            if (theClient == null) {
                theClient = new ERDAClient(storageConfig);
            }
            theClient.testAndRestore();
            return theClient;
        } catch (Exception e) {
            logger.warn("ERDA Client failed");
            if (theClient != null) {
                this.lastFailure = Instant.now();
            }
            throw new RuntimeException("Failed to get client", e);
        }

    }
}
