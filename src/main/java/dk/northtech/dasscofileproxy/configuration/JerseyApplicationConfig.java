package dk.northtech.dasscofileproxy.configuration;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DasscoIllegalActionExceptionMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.IllegalArguementExceptionMapper;
import dk.northtech.dasscofileproxy.webapi.v1.*;
import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.springframework.stereotype.Component;

@Component
@ApplicationPath("/file_proxy/api")
public class JerseyApplicationConfig extends ResourceConfig {
  public JerseyApplicationConfig() {
    // Activate the designated JaxRs classes with API endpoints:
    register(FtpsClient.class);
    register(SFTPApi.class);
    register(RolesAllowedDynamicFeature.class);
    register(ClientAbortInterceptor.class);
    register(IllegalArguementExceptionMapper.class);
    register(DasscoIllegalActionExceptionMapper.class);
    register(Files.class);
    register(Assets.class);
    register(HttpShareAPI.class);
    register(OpenAPI.class);
  }
}
