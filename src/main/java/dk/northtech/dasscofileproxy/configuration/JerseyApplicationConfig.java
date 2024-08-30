package dk.northtech.dasscofileproxy.configuration;

import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSScoExceptionMapper;
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
    register(DaSScoExceptionMapper.class);
    register(AssetFiles.class);
    register(Assets.class);
    register(HttpShareAPI.class);
    register(OpenAPI.class);
    register(Logs.class);
    register(DebugTools.class);
    register(AssetFiles.class);
    register(Files.class);
  }
}
