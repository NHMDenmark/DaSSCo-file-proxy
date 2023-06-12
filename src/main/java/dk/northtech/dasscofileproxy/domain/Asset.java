package dk.northtech.dasscofileproxy.domain;

public record Asset(
        String institution,
        String collection,
        String assetGuid
) {
}
