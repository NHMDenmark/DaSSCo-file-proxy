package dk.northtech.dasscofileproxy.configuration;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth")
public record AuthConfiguration(String serverUrl, String clientName) {
    public AuthConfiguration {
        serverUrl = withoutTrailingSlash(serverUrl);
    }

    private static String withoutTrailingSlash(String s) {
        s = s.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
