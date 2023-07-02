package dk.northtech.dasscofileproxy.domain;

import java.util.HashSet;
import java.util.Set;

public class User {
    public String username;
    public String token;
    public String keycloakId;
    public Set<String> roles = new HashSet<>();
}
