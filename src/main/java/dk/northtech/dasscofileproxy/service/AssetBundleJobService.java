package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.User;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class AssetBundleJobService {
    private static final Duration MANUAL_TEST_DELAY = Duration.ZERO;
    private static final Duration ABANDONED_JOB_TTL = Duration.ofHours(12);

    private final AssetBundleCreator assetBundleCreator;
    private final ExternalAssetBundleCreator externalAssetBundleCreator;
    private final Executor executor;
    private final Duration assetBundleJobDelay;
    private final Duration assetBundleJobTtl;
    private final ConcurrentHashMap<String, AssetBundleJob> jobs = new ConcurrentHashMap<>();

    @Inject
    public AssetBundleJobService(AssetBundleCreator assetBundleCreator, ExternalAssetBundleCreator externalAssetBundleCreator) {
        this(assetBundleCreator, externalAssetBundleCreator, new ThreadPoolExecutor(
                1,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy()
        ), MANUAL_TEST_DELAY, ABANDONED_JOB_TTL);
    }

    AssetBundleJobService(AssetBundleCreator assetBundleCreator, Executor executor) {
        this(assetBundleCreator, assetBundleCreator::create, executor, Duration.ZERO, Duration.ofHours(1));
    }

    AssetBundleJobService(AssetBundleCreator assetBundleCreator, Executor executor, Duration assetBundleJobDelay) {
        this(assetBundleCreator, assetBundleCreator::create, executor, assetBundleJobDelay, Duration.ofHours(1));
    }

    AssetBundleJobService(AssetBundleCreator assetBundleCreator, ExternalAssetBundleCreator externalAssetBundleCreator, Executor executor) {
        this(assetBundleCreator, externalAssetBundleCreator, executor, Duration.ZERO, Duration.ofHours(1));
    }

    AssetBundleJobService(AssetBundleCreator assetBundleCreator, ExternalAssetBundleCreator externalAssetBundleCreator, Executor executor, Duration assetBundleJobDelay) {
        this(assetBundleCreator, externalAssetBundleCreator, executor, assetBundleJobDelay, Duration.ofHours(1));
    }

    AssetBundleJobService(AssetBundleCreator assetBundleCreator, ExternalAssetBundleCreator externalAssetBundleCreator, Executor executor, Duration assetBundleJobDelay, Duration assetBundleJobTtl) {
        this.assetBundleCreator = assetBundleCreator;
        this.externalAssetBundleCreator = externalAssetBundleCreator;
        this.executor = executor;
        this.assetBundleJobDelay = assetBundleJobDelay == null ? Duration.ZERO : assetBundleJobDelay;
        this.assetBundleJobTtl = assetBundleJobTtl == null ? Duration.ofHours(1) : assetBundleJobTtl;
    }

    public AssetBundleJobSnapshot start(List<String> assetGuids, User user) {
        String jobId = UUID.randomUUID().toString();
        AssetBundleJob job = new AssetBundleJob(jobId, List.copyOf(assetGuids), AssetBundleJobType.INTERNAL, ownerKey(user));
        jobs.put(jobId, job);

        startJob(assetGuids, user, job, assetBundleCreator::create);
        return job.snapshot();
    }

    public AssetBundleJobSnapshot startExternal(List<String> assetGuids, User user) {
        String jobId = UUID.randomUUID().toString();
        AssetBundleJob job = new AssetBundleJob(jobId, List.copyOf(assetGuids), AssetBundleJobType.EXTERNAL, null);
        jobs.put(jobId, job);

        startJob(assetGuids, user, job, externalAssetBundleCreator::createExternal);
        return job.snapshot();
    }

    private void startJob(List<String> assetGuids, User user, AssetBundleJob job, BundleCreationOperation creator) {
        try {
            executor.execute(() -> {
                try {
                    applyManualTestDelay();
                    File bundleFile = creator.create(assetGuids, user);
                    if (job.isCancelled()) {
                        deleteBundleFile(bundleFile);
                    } else {
                        job.ready(bundleFile);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.failed("Asset bundle preparation was interrupted");
                } catch (Exception e) {
                    job.failed(e.getMessage() == null ? "Failed to create asset bundle" : e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            job.failed("Asset bundle preparation queue is full");
        }
    }

    @FunctionalInterface
    private interface BundleCreationOperation {
        File create(List<String> assetGuids, User user) throws Exception;
    }

    public Optional<AssetBundleJobSnapshot> get(String jobId, AssetBundleJobType jobType, User user) {
        return Optional.ofNullable(jobs.get(jobId))
                .filter(job -> job.canAccess(jobType, ownerKey(user)))
                .map(AssetBundleJob::snapshot);
    }

    public Optional<File> getBundleFile(String jobId, AssetBundleJobType jobType, User user) {
        AssetBundleJob job = jobs.get(jobId);
        if (job == null || !job.canAccess(jobType, ownerKey(user)) || job.status != AssetBundleJobStatus.READY || job.bundleFile == null) {
            return Optional.empty();
        }
        return Optional.of(job.bundleFile);
    }

    public Optional<File> getBundleFileForDownload(String jobId, AssetBundleJobType jobType, User user) {
        AssetBundleJob job = jobs.get(jobId);
        if (job == null || !job.canAccess(jobType, ownerKey(user)) || job.status != AssetBundleJobStatus.READY || job.bundleFile == null) {
            return Optional.empty();
        }
        return Optional.of(job.bundleFile);
    }

    public boolean startDownload(String jobId, AssetBundleJobType jobType, User user) {
        AssetBundleJob job = jobs.get(jobId);
        if (job == null || !job.canAccess(jobType, ownerKey(user)) || job.status != AssetBundleJobStatus.READY || job.bundleFile == null) {
            return false;
        }
        job.startDownload();
        return true;
    }

    public void finishDownload(String jobId) {
        AssetBundleJob job = jobs.get(jobId);
        if (job != null) {
            job.finishDownload();
        }
    }

    public void complete(String jobId) {
        AssetBundleJob job = jobs.remove(jobId);
        deleteBundleFile(job);
    }

    public boolean cancel(String jobId, AssetBundleJobType jobType, User user) {
        AssetBundleJob job = jobs.get(jobId);
        if (job == null || !job.canAccess(jobType, ownerKey(user))) {
            return false;
        }
        job.cancel();
        AssetBundleJob removed = jobs.remove(jobId);
        deleteBundleFile(removed);
        return true;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000)
    public void cleanupExpiredJobs() {
        Instant cutoff = Instant.now().minus(assetBundleJobTtl);
        jobs.forEach((jobId, job) -> {
            if (job.isExpired(cutoff)) {
                AssetBundleJob removed = jobs.remove(jobId);
                deleteBundleFile(removed);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdown();
        }
    }

    private void deleteBundleFile(AssetBundleJob job) {
        if (job != null) {
            deleteBundleFile(job.bundleFile);
        }
    }

    private void deleteBundleFile(File bundleFile) {
        if (bundleFile != null && bundleFile.exists() && !bundleFile.delete()) {
            bundleFile.deleteOnExit();
        }
    }

    private void applyManualTestDelay() throws InterruptedException {
        if (assetBundleJobDelay.isZero() || assetBundleJobDelay.isNegative()) {
            return;
        }
        Thread.sleep(assetBundleJobDelay.toMillis());
    }

    private String ownerKey(User user) {
        if (user == null) {
            return null;
        }
        if (user.keycloakId != null && !user.keycloakId.isBlank()) {
            return user.keycloakId;
        }
        return user.username;
    }

    private static class AssetBundleJob {
        private final String jobId;
        private final int totalAssets;
        private final AssetBundleJobType jobType;
        private final String ownerKey;
        private volatile AssetBundleJobStatus status;
        private volatile int processedAssets;
        private volatile String message;
        private volatile File bundleFile;
        private volatile Instant finishedAt;
        private final java.util.concurrent.atomic.AtomicInteger activeDownloads = new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean cancelled;

        private AssetBundleJob(String jobId, List<String> assetGuids, AssetBundleJobType jobType, String ownerKey) {
            this.jobId = jobId;
            this.totalAssets = assetGuids.size();
            this.jobType = jobType;
            this.ownerKey = ownerKey;
            this.status = AssetBundleJobStatus.PREPARING;
            this.processedAssets = 0;
            this.message = "Preparing asset bundle";
        }

        private void ready(File bundleFile) {
            this.bundleFile = bundleFile;
            this.processedAssets = totalAssets;
            this.message = "Asset bundle is ready";
            this.finishedAt = Instant.now();
            this.status = AssetBundleJobStatus.READY;
        }

        private void failed(String message) {
            this.message = message;
            this.finishedAt = Instant.now();
            this.status = AssetBundleJobStatus.FAILED;
        }

        private AssetBundleJobSnapshot snapshot() {
            return new AssetBundleJobSnapshot(jobId, status, totalAssets, processedAssets, message);
        }

        private boolean canAccess(AssetBundleJobType requestedType, String requestedOwnerKey) {
            if (jobType != requestedType) {
                return false;
            }
            if (jobType == AssetBundleJobType.EXTERNAL) {
                return true;
            }
            return ownerKey != null && ownerKey.equals(requestedOwnerKey);
        }

        private boolean isExpired(Instant cutoff) {
            return status != AssetBundleJobStatus.PREPARING
                    && activeDownloads.get() == 0
                    && finishedAt != null
                    && finishedAt.isBefore(cutoff);
        }

        private void startDownload() {
            activeDownloads.incrementAndGet();
        }

        private void finishDownload() {
            activeDownloads.updateAndGet(count -> Math.max(0, count - 1));
        }

        private void cancel() {
            this.cancelled = true;
        }

        private boolean isCancelled() {
            return cancelled;
        }
    }
}
