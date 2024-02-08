package dk.northtech.dasscofileproxy.domain;

public record DasscoFile(Long fileId, String assetGuid, String path, long sizeBytes, long crc) {
}
