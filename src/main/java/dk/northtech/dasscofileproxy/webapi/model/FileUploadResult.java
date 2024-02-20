package dk.northtech.dasscofileproxy.webapi.model;

public record FileUploadResult(long expected_crc, long actual_crc) {
    public int getResponseCode() {
        return expected_crc == actual_crc ? 200 : 507;
    }
}
