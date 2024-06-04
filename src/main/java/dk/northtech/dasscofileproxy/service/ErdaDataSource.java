package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

public class ErdaDataSource extends ResourcePool<ERDAClient> {
    private final SFTPConfig sftpConfig;
    private static final Logger logger = LoggerFactory.getLogger(ErdaDataSource.class);

    public ErdaDataSource(int size, Boolean dynamicCreation, SFTPConfig sftpConfig) {
        super(size, dynamicCreation);
        this.sftpConfig = sftpConfig;
    }

    @Override
    protected ERDAClient createObject() {
        return new ERDAClient(sftpConfig);
    }

    @Override
    public ERDAClient acquire() {
        try {
            ERDAClient acquire = super.acquire();
            try {
                acquire.testAndRestore();
                return acquire;
            } catch (Exception e) {
                logger.warn("Failed to get ERDAClient, maybe ERDA is down?");
                // Return failed connection to pool, it is likely a time based error and will self-correct after some time.
                recycle(acquire);
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get ERDA client: unknown error", e);
        }
//        throw new RuntimeException("Failed to get ERDA client: unknown error", e);

    }
}
