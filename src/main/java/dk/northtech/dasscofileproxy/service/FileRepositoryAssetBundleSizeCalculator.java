package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.repository.FileRepository;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FileRepositoryAssetBundleSizeCalculator implements AssetBundleSizeCalculator {
    private final Jdbi jdbi;

    @Inject
    public FileRepositoryAssetBundleSizeCalculator(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public long totalSizeBytes(List<String> assetGuids) {
        if (assetGuids == null || assetGuids.isEmpty()) {
            return 0;
        }

        Set<String> uniqueAssetGuids = assetGuids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueAssetGuids.isEmpty()) {
            return 0;
        }

        return jdbi.withHandle(handle -> handle.attach(FileRepository.class).getTotalAllocatedByAsset(uniqueAssetGuids));
    }
}
