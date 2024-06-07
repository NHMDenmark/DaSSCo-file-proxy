package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ErdaDataSource2 {
    private final SFTPConfig sftpConfig;
    private static final Logger logger = LoggerFactory.getLogger(ErdaDataSource2.class);
    Instant lastFailure = null;
    private ERDAClient theClient = null;
    public ErdaDataSource2(SFTPConfig sftpConfig) {
        this.sftpConfig = sftpConfig;
    }

    public ERDAClient getClient() {
        try {
            if(theClient == null) {
                theClient = new ERDAClient(sftpConfig);
            }
            theClient.testAndRestore();
            return theClient;
        } catch (Exception e) {
            logger.warn("ERDA Client failed");
            if(theClient != null) {
                this.lastFailure = Instant.now();
            }
            throw new RuntimeException("Failed to get client", e);
        }

    }
}
