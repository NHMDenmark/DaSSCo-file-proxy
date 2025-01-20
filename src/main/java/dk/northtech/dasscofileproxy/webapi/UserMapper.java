package dk.northtech.dasscofileproxy.webapi;

import dk.northtech.dasscofileproxy.domain.SecurityRoles;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoUnauthorizedException;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;

public class UserMapper {
    private static final Logger logger = LoggerFactory.getLogger(UserMapper.class);
    public static User from(SecurityContext securityContext) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) securityContext.getUserPrincipal();
        if(token == null) {
            logger.warn("Unauthorized user");
            throw new DasscoUnauthorizedException();
        }
        Map<String, Object> tokenAttributes = token.getTokenAttributes();
        User user = new User();
        if(securityContext.isUserInRole(SecurityRoles.ADMIN)) {
            user.roles.add(SecurityRoles.ADMIN);
        }
        if(securityContext.isUserInRole(SecurityRoles.USER)) {
            user.roles.add(SecurityRoles.USER);
        }
        if(securityContext.isUserInRole(SecurityRoles.DEVELOPER)) {
            user.roles.add(SecurityRoles.DEVELOPER);
        }
        if(securityContext.isUserInRole(SecurityRoles.SERVICE)) {
            user.roles.add(SecurityRoles.SERVICE);
        }
        user.keycloakId = String.valueOf(tokenAttributes.get("sub"));
        user.username = String.valueOf(tokenAttributes.get("preferred_username"));
        user.token = token.getToken().getTokenValue();
        return user;
    }
}
