package dk.northtech.dasscofileproxy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParkingServiceTest {

    @Test
    void shouldReplaceMatchingAttachmentLocationTokenCaseSensitive() {
        String renamed = ParkingService.getRenamedParkedFileName(
                "abc_LOC-123_def_LOC-123.tif",
                "LOC-123.tif",
                "asset-guid",
                false
        );

        assertEquals("abc_asset-guid_def_asset-guid.tif", renamed);
    }

    @Test
    void shouldNotReplaceWhenCaseDoesNotMatch() {
        String renamed = ParkingService.getRenamedParkedFileName(
                "abc_loc-123_def.tif",
                "LOC-123.tif",
                "asset-guid",
                false
        );

        assertEquals("abc_loc-123_def.tif", renamed);
    }

    @Test
    void shouldStripExtensionFromAttachmentLocationBeforeMatching() {
        String renamed = ParkingService.getRenamedParkedFileName(
                "before_LOC-123_after.png",
                "LOC-123.png",
                "asset-guid",
                false
        );

        assertEquals("before_asset-guid_after.png", renamed);
    }

    @Test
    void shouldFallbackRenameToAssetGuidWhenSingleFileAndNoMatch() {
        String renamed = ParkingService.getRenamedParkedFileName(
                "unrelated-name.tif",
                "LOC-123.tif",
                "asset-guid",
                true
        );

        assertEquals("asset-guid.tif", renamed);
    }

    @Test
    void shouldKeepOriginalNameWhenNoMatchAndMultipleFiles() {
        String renamed = ParkingService.getRenamedParkedFileName(
                "unrelated-name.tif",
                "LOC-123.tif",
                "asset-guid",
                false
        );

        assertEquals("unrelated-name.tif", renamed);
    }
}
