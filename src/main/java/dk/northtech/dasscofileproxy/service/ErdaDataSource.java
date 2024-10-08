package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ErdaDataSource extends ResourcePool<ERDAClient> {
    private final SFTPConfig sftpConfig;
    private static final Logger logger = LoggerFactory.getLogger(ErdaDataSource.class);
    Instant lastFailure = null;


    public ErdaDataSource(Boolean dynamicCreation, SFTPConfig sftpConfig) {
        super(sftpConfig.erdaConnectionPoolSize(), dynamicCreation);
        this.sftpConfig = sftpConfig;
    }

    private final Object lock = new Object();
    private List<ERDAClient> deadClients = Collections.synchronizedList(new ArrayList<>());

    @Override
    protected ERDAClient createObject() {
        return new ERDAClient(sftpConfig, this);
    }

    @Override
    public ERDAClient acquire(int maxSeconds) {
        int attempts = 3;
        ERDAClient erdaClient = null;
        for (int i = 0; i < attempts; i++) {
            try {
                erdaClient = acquireInternal(maxSeconds);
                return erdaClient;
            } catch (Exception e) {
                logger.error("Failed to get ERDA client, attempt {}", i + 1);
            }
        }
        throw new RuntimeException("No ERDA connection is available");
    }

    public ERDAClient acquireInternal(int maxSeconds) throws Exception {
//        try {
        ERDAClient acquire = super.acquire(maxSeconds);
        if (acquire == null) {
            return null;
        }
        try {
            acquire.testAndThrow();
            return acquire;
        } catch (Exception e) {
            logger.warn("Failed to get ERDAClient");
            synchronized (lock) {
                deadClients.add(acquire);
            }
            // Don't reset last failure if it happened within the last 5 minutes (ERDA failure typically lasts 5 minutes).
            if (lastFailure == null || Instant.now().minusSeconds(310).isBefore(lastFailure)) {
                lastFailure = Instant.now();
            }
            logger.error("Failed to aquire ERDA connection, ", e);
            throw new RuntimeException(e);
        }
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to get ERDA client: unknown error", e);
//        }

    }

    ERDAClient acquire() throws Exception {
        return this.acquire(30);
    }


    //At random second to prevent other timed tasks to overlap and attempt to get Clients.
    @Scheduled(cron = "33 */1 * * * *")
    public void reviveClients() {
//        logger.info("checking for failed ERDAClients");
        logger.warn("There are {} failed ERDAClients", deadClients.size());
        logger.info("last failure {}", lastFailure);
        if (!deadClients.isEmpty() && lastFailure != null && Instant.now().minusSeconds(310).isAfter(lastFailure)) {
            ArrayList<ERDAClient> erdaClients;
            logger.warn("Trying to restore ERDAClients");
            synchronized (lock) {
                erdaClients = new ArrayList<>(deadClients);
                this.deadClients = new ArrayList<>();
            }
            logger.warn("There are {} failed ERDAClients", erdaClients.size());
            for (ERDAClient erdaClient : erdaClients) {
                if (checkAndAddCreationTime()) {
                    try {
                        erdaClient.testAndRestore();
                        logger.info("Restored one ERDAClient");
                        recycle(erdaClient);
                    } catch (Exception e) {
                        logger.info("Failed to restore an ERDAClient");
                        this.deadClients.add(erdaClient);
                    }
                } else {
                    deadClients.add(erdaClient);
                }
            }
        }
    }
}
