package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.service.DockerService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.service.SambaServerService;
import dk.northtech.dasscofileproxy.webapi.UserMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/samba")
public class SambaServerApi {

    DockerConfig dockerConfig;
    FileService fileService;
    SambaServerService sambaServerService;
    DockerService dockerService;

    @Inject
    public SambaServerApi(DockerConfig dockerConfig, DockerService dockerService, FileService fileService, SambaServerService sambaServerService) {
        this.dockerConfig = dockerConfig;
        this.dockerService = dockerService;
        this.fileService = fileService;
        this.sambaServerService = sambaServerService;
    }

    @POST
    @Path("/createShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public SambaInfo createSambaServer(CreationObj creationObj, @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        return sambaServerService.createSambaServer(creationObj, user);

    }

    @POST
    @Path("/disconnectShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN, SecurityRoles.ADMIN})
    public SambaInfo disconnectSambaServer(AssetSmbRequest assetSmbRequest, @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        return sambaServerService.disconnect(assetSmbRequest, user);
    }

    @POST
    @Path("/closeShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public SambaInfo closeSambaServer(AssetUpdateRequest assetUpdateRequest
            , @QueryParam("syncERDA") Boolean syncERDA
            , @Context SecurityContext securityContext) {
        boolean adminAction = securityContext.isUserInRole(SecurityRoles.ADMIN);
        User user = UserMapper.from(securityContext);
        sambaServerService.close(assetUpdateRequest, user, adminAction, syncERDA);
        return new SambaInfo(null, null, assetUpdateRequest.shareName(), null, SambaRequestStatus.OK_CLOSED, null);
    }

    @POST
    @Path("/openShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public SambaInfo openSambaServer(AssetSmbRequest assetSmbRequest
            , @Context SecurityContext securityContext) {
        User user = UserMapper.from(securityContext);
        SambaServer open = sambaServerService.open(assetSmbRequest, user);
        return new SambaInfo(open.containerPort(), dockerConfig.dockerHost(), "share_" + open.sambaServerId(), open.userAccess().get(0).token(), SambaRequestStatus.OK_OPEN, null);
    }
}
