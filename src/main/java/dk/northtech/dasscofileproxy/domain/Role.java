package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.Optional;

@Schema(description = "Possible roles for the User", example = "ADMIN")
public enum Role {
    USER(SecurityRoles.USER)
    , ADMIN(SecurityRoles.ADMIN)
    , SERVICE_USER(SecurityRoles.SERVICE)
    , DEVELOPER(SecurityRoles.DEVELOPER);
    public final String roleName;
    Role(String role) {
        this.roleName = role;
    }

    Role getRole(String name) {
        Optional<Role> optRole = Arrays.stream(values()).filter(role -> role.roleName.equals(name)).findFirst();
        return optRole.orElseThrow(() -> new IllegalArgumentException("Role ["+name+"] not found"));
    }
}
