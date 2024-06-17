package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.domain.SecurityRoles;
import dk.northtech.dasscofileproxy.domain.User;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.service.LogService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import dk.northtech.dasscofileproxy.webapi.exceptionmappers.DaSSCoError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.tika.Tika;

import java.util.List;
import java.util.Optional;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/logs")
@Tag(name = "Log Files", description = "Endpoints for getting log files")
@SecurityRequirement(name = "dassco-idp")
public class Logs {
    private final LogService logService;

    @Inject
    public Logs(LogService logService) {
        this.logService = logService;
    }

    @GET
    @Path("/")
    @Operation(summary = "List logfiles", description = "List all logfiles in the log directory")
    @Produces(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "Returns the file.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN})
    public List<String> listLogs(
             @Context SecurityContext securityContext
    ) {

        return logService.listLogs();
    }

    @GET
    @Path("/{fileName}")
    @Operation(summary = "Get a log file", description = "Get a log file by name, use list endpoint to see names.")
    @Consumes(APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "Returns the file.")
    @ApiResponse(responseCode = "400-599", content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(implementation = DaSSCoError.class)))
    @RolesAllowed({SecurityRoles.DEVELOPER, SecurityRoles.ADMIN})
    public Response getFile(
            @Context SecurityContext securityContext
            , @PathParam("fileName") String name
    ) {
        User user = UserMapper.from(securityContext);

        Optional<FileService.FileResult> getFileResult = logService.getFile(name);
        if (getFileResult.isPresent()) {
            FileService.FileResult fileResult = getFileResult.get();
            StreamingOutput streamingOutput = output -> {
                fileResult.is().transferTo(output);
                output.flush();
            };
            return Response.status(200)
                    .header("Content-Disposition", "attachment; filename=" + fileResult.filename())
                    .header("Content-Type", new Tika().detect(fileResult.filename())).entity(streamingOutput).build();
        }
        return Response.status(404).build();
    }

}
