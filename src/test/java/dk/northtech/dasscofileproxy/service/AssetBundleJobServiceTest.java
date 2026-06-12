package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.User;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.google.common.truth.Truth.assertThat;

class AssetBundleJobServiceTest {

    @Test
    void startBundleJob_returnsPreparingBeforeWorkerRuns_andReadyAfterWorkerCompletes() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        File bundle = File.createTempFile("asset-bundle-job", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService((assetGuids, user) -> bundle, executor);

        AssetBundleJobSnapshot started = service.start(List.of("asset-1", "asset-2"), new User("user"));

        assertThat(started.status()).isEqualTo(AssetBundleJobStatus.PREPARING);
        assertThat(started.totalAssets()).isEqualTo(2);
        assertThat(started.processedAssets()).isEqualTo(0);

        executor.runRecordedCommand();

        Optional<AssetBundleJobSnapshot> completed = service.get(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"));
        assertThat(completed).isPresent();
        assertThat(completed.get().status()).isEqualTo(AssetBundleJobStatus.READY);
        assertThat(completed.get().processedAssets()).isEqualTo(2);
        assertThat(service.getBundleFile(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"))).hasValue(bundle);

        bundle.delete();
    }

    @Test
    void startBundleJob_marksJobFailedWhenBundleCreationFails() {
        RecordingExecutor executor = new RecordingExecutor();
        AssetBundleJobService service = new AssetBundleJobService((assetGuids, user) -> {
            throw new RuntimeException("ERDA unavailable");
        }, executor);

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), new User("user"));
        executor.runRecordedCommand();

        Optional<AssetBundleJobSnapshot> failed = service.get(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"));
        assertThat(failed).isPresent();
        assertThat(failed.get().status()).isEqualTo(AssetBundleJobStatus.FAILED);
        assertThat(failed.get().message()).isEqualTo("ERDA unavailable");
        assertThat(service.getBundleFile(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"))).isEmpty();
    }

    @Test
    void startExternalBundleJob_usesExternalCreator() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        File internalBundle = File.createTempFile("asset-bundle-job-internal", ".zip");
        File externalBundle = File.createTempFile("asset-bundle-job-external", ".zip");
        AssetBundleJobService service = new AssetBundleJobService(
                (assetGuids, user) -> internalBundle,
                (assetGuids, user) -> externalBundle,
                executor
        );

        AssetBundleJobSnapshot started = service.startExternal(List.of("asset-1"), new User("anonymous"));
        executor.runRecordedCommand();

        assertThat(service.getBundleFile(started.jobId(), AssetBundleJobType.EXTERNAL, new User("anonymous"))).hasValue(externalBundle);

        internalBundle.delete();
        externalBundle.delete();
    }

    @Test
    void get_rejectsCrossTypeAccess() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        File internalBundle = File.createTempFile("asset-bundle-job-internal", ".zip");
        AssetBundleJobService service = new AssetBundleJobService(
                (assetGuids, user) -> internalBundle,
                (assetGuids, user) -> {
                    throw new AssertionError("external creator should not be called");
                },
                executor
        );

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), user("owner-key"));
        executor.runRecordedCommand();

        assertThat(service.get(started.jobId(), AssetBundleJobType.EXTERNAL, new User("anonymous"))).isEmpty();
        assertThat(service.getBundleFile(started.jobId(), AssetBundleJobType.EXTERNAL, new User("anonymous"))).isEmpty();

        internalBundle.delete();
    }

    @Test
    void get_rejectsInternalJobForDifferentUser() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        File internalBundle = File.createTempFile("asset-bundle-job-owner", ".zip");
        AssetBundleJobService service = new AssetBundleJobService(
                (assetGuids, user) -> internalBundle,
                (assetGuids, user) -> {
                    throw new AssertionError("external creator should not be called");
                },
                executor
        );

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), user("owner-key"));
        executor.runRecordedCommand();

        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, user("other-key"))).isEmpty();
        assertThat(service.getBundleFile(started.jobId(), AssetBundleJobType.INTERNAL, user("other-key"))).isEmpty();
        assertThat(service.getBundleFile(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).hasValue(internalBundle);

        internalBundle.delete();
    }

    @Test
    void get_returnsEmptyForUnknownJob() {
        AssetBundleJobService service = new AssetBundleJobService((assetGuids, user) -> {
            throw new AssertionError("creator should not be called");
        }, Runnable::run);

        assertThat(service.get("missing-job", AssetBundleJobType.INTERNAL, new User("user"))).isEmpty();
        assertThat(service.getBundleFile("missing-job", AssetBundleJobType.INTERNAL, new User("user"))).isEmpty();
    }

    @Test
    void complete_removesJobAndDeletesBundleFile() throws Exception {
        File bundle = File.createTempFile("asset-bundle-job-cleanup", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService((assetGuids, user) -> bundle, Runnable::run);

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), new User("user"));

        service.complete(started.jobId());

        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).isEmpty();
        assertThat(bundle.exists()).isFalse();
    }

    @Test
    void cleanupExpiredJobs_removesJobAndDeletesBundleFile() throws Exception {
        File bundle = File.createTempFile("asset-bundle-job-expired", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService(
                (assetGuids, user) -> bundle,
                (assetGuids, user) -> {
                    throw new AssertionError("external creator should not be called");
                },
                Runnable::run,
                Duration.ZERO,
                Duration.ZERO
        );

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), user("owner-key"));

        service.cleanupExpiredJobs();

        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).isEmpty();
        assertThat(bundle.exists()).isFalse();
    }

    @Test
    void cleanupExpiredJobs_doesNotRemoveBundleDuringActiveDownload() throws Exception {
        File bundle = File.createTempFile("asset-bundle-job-downloading", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService(
                (assetGuids, user) -> bundle,
                (assetGuids, user) -> {
                    throw new AssertionError("external creator should not be called");
                },
                Runnable::run,
                Duration.ZERO,
                Duration.ZERO
        );

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), user("owner-key"));
        assertThat(service.getBundleFileForDownload(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).hasValue(bundle);
        assertThat(service.startDownload(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).isTrue();

        service.cleanupExpiredJobs();

        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).isPresent();
        assertThat(bundle.exists()).isTrue();

        service.complete(started.jobId());
    }

    @Test
    void cleanupExpiredJobs_removesBundleAfterActiveDownloadFinishes() throws Exception {
        File bundle = File.createTempFile("asset-bundle-job-download-finished", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService(
                (assetGuids, user) -> bundle,
                (assetGuids, user) -> {
                    throw new AssertionError("external creator should not be called");
                },
                Runnable::run,
                Duration.ZERO,
                Duration.ZERO
        );

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), user("owner-key"));
        assertThat(service.startDownload(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).isTrue();
        service.finishDownload(started.jobId());

        service.cleanupExpiredJobs();

        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).isEmpty();
        assertThat(bundle.exists()).isFalse();
    }

    @Test
    void cancel_removesReadyJobAndDeletesBundleFile() throws Exception {
        File bundle = File.createTempFile("asset-bundle-job-cancel-ready", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService((assetGuids, user) -> bundle, Runnable::run);

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), new User("user"));

        assertThat(service.cancel(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"))).isTrue();
        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"))).isEmpty();
        assertThat(bundle.exists()).isFalse();
    }

    @Test
    void cancelBeforeWorkerCompletes_removesJobAndDeletesBundleCreatedLater() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        File bundle = File.createTempFile("asset-bundle-job-cancel-preparing", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService((assetGuids, user) -> bundle, executor);

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), new User("user"));

        assertThat(service.cancel(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"))).isTrue();
        executor.runRecordedCommand();

        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, new User("user"))).isEmpty();
        assertThat(bundle.exists()).isFalse();
    }

    @Test
    void cancel_rejectsWrongTypeAndWrongUser() throws Exception {
        File bundle = File.createTempFile("asset-bundle-job-cancel-owner", ".zip");
        try (FileWriter writer = new FileWriter(bundle)) {
            writer.write("zip content");
        }
        AssetBundleJobService service = new AssetBundleJobService(
                (assetGuids, user) -> bundle,
                (assetGuids, user) -> {
                    throw new AssertionError("external creator should not be called");
                },
                Runnable::run
        );

        AssetBundleJobSnapshot started = service.start(List.of("asset-1"), user("owner-key"));

        assertThat(service.cancel(started.jobId(), AssetBundleJobType.EXTERNAL, new User("anonymous"))).isFalse();
        assertThat(service.cancel(started.jobId(), AssetBundleJobType.INTERNAL, user("other-key"))).isFalse();
        assertThat(service.get(started.jobId(), AssetBundleJobType.INTERNAL, user("owner-key"))).isPresent();
        assertThat(bundle.exists()).isTrue();

        service.complete(started.jobId());
    }

    private static class RecordingExecutor implements Executor {
        private Runnable command;

        @Override
        public void execute(Runnable command) {
            this.command = command;
        }

        void runRecordedCommand() {
            command.run();
        }
    }

    private static User user(String keycloakId) {
        User user = new User("user-" + keycloakId);
        user.keycloakId = keycloakId;
        return user;
    }
}
