package dk.northtech.dasscofileproxy.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Possible roles for Daasco", example = "daasco-admin")
public interface SecurityRoles {
    String ADMIN = "dassco-admin";
    String DEVELOPER = "dassco-developer";
    String USER = "dassco-user";
    String SERVICE = "service-user";
}
