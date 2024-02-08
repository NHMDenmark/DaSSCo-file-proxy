package dk.northtech.dasscofileproxy.webapi.model;

public record FileUploadResult(long expectedCRC, long actualCRC) {
    public int getResponseCode() {
        return expectedCRC == actualCRC ? 200 : 507;
    }
}
