package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import dk.northtech.dasscofileproxy.domain.SecurityRoles;
import dk.northtech.dasscofileproxy.domain.exceptions.DasscoInternalErrorException;
import dk.northtech.dasscofileproxy.service.ERDAClient;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.HashMap;
import java.util.LinkedHashMap;

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


    @Operation(summary = "Force erda disconnect", description = "Disconnects ERDA by intentionally creating too many connections")
    @ApiResponse(responseCode = "200", description = "ERDA ")
    @POST
    @Hidden
    @Path("/forceerdadisconnect")
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN})
    public void disconnectErda(
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

    @Operation(summary = "Get Asset File by path", description = "Get an asset file based on institution, collection, asset_guid and path to the file")
    @ApiResponse(responseCode = "200", description = "ERDA ")
    @GET
    @Hidden
    @Path("/logfilestores")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN})
    public HashMap<String, Object> logFileStores(
            @Context SecurityContext securityContext
    ) {
        HashMap<String, Object> result = new LinkedHashMap<>();
        //This exhausts ERDA connections and disconnects all ERDA client for the next 5 minutes
        for (java.nio.file.Path root : FileSystems.getDefault().getRootDirectories()) {
            try {
                result.put("root", root.toString());
                FileStore store = java.nio.file.Files.getFileStore(root);
                result.put("usable", store.getUsableSpace());
                result.put("total", store.getTotalSpace());
            } catch (IOException e) {
                System.out.println("error querying space: " + e.toString());
            }
        }
        return result;
    }


}
