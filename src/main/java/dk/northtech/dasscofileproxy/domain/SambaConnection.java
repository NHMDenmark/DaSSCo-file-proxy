package dk.northtech.dasscofileproxy.domain;

public record SambaConnection(String hostName, Integer port, String shareName, String token) {
}
