package dk.northtech.dasscofileproxy.configuration;

import dk.northtech.dasscofileproxy.webapi.v1.SambaServerApi;
import dk.northtech.dasscofileproxy.webapi.v1.FtpsClient;
import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.springframework.stereotype.Component;

@Component
@ApplicationPath("/api")
public class JerseyApplicationConfig extends ResourceConfig {
  public JerseyApplicationConfig() {
    // Activate the designated JaxRs classes with API endpoints:
    register(SambaServerApi.class);
    register(FtpsClient.class);

    register(RolesAllowedDynamicFeature.class);
    register(ClientAbortInterceptor.class);
  }
}
