package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoInternalErrorException;
import dk.northtech.dasscofileproxy.service.ERDAClient;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/debugtools")
@Tag(name = "Debug tools", description = "Endpoints for debugging")
@SecurityRequirement(name = "dassco-idp")
public class DebugTools {
    private static final Logger logger = LoggerFactory.getLogger(DebugTools.class);
    private final SFTPConfig sftpConfig;

    @Inject
    public DebugTools(SFTPConfig sftpConfig) {
        this.sftpConfig = sftpConfig;
    }


    @POST
    @Hidden
    @Path("/forceerdadisconnect")
    @Operation(summary = "Get Asset File by path", description = "Get an asset file based on institution, collection, asset_guid and path to the file")
    @ApiResponse(responseCode = "200", description = "ERDA ")
    public void listLogs(
            @Context SecurityContext securityContext
    ) {
        //This exhausts ERDA connections and disconnects all ERDA client for the next 5 minutes
        for (int i = 0; i < 100; i++) {
            try (ERDAClient erdaClient = new ERDAClient(sftpConfig);) {
                erdaClient.testAndRestore();
                logger.info("Created {} clients",i+1);
            } catch (Exception e) {
                return;
            }
        }
        throw new DasscoInternalErrorException("ERDA still works :^(");
    }


}
