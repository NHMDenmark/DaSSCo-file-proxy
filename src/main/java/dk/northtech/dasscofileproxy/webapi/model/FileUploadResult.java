package dk.northtech.dasscofileproxy.webapi.model;

import io.swagger.v3.oas.annotations.media.Schema;

public record FileUploadResult(
        @Schema(description = "Expected CRC for the File", example = "123")
        long expected_crc,
        @Schema(description = "Actual CRC for the File", example = "123")
        long actual_crc) {
    public int getResponseCode() {
        return expected_crc == actual_crc ? 200 : 507;
    }
}
