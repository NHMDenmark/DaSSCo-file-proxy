package dk.northtech.dasscofileproxy.configuration;

import dk.northtech.dasscofileproxy.webapi.v1.HelloWorld;
import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.springframework.stereotype.Component;

@Component
@ApplicationPath("/api")
public class JerseyApplicationConfig extends ResourceConfig {
  public JerseyApplicationConfig() {
    // Activate the designated JaxRs classes with API endpoints:
    register(HelloWorld.class);

    register(RolesAllowedDynamicFeature.class);
    register(ClientAbortInterceptor.class);
  }
}
