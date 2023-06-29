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
    public SambaInfo createSambaServer(CreationObj creationObj) {
        Instant creationDatetime = Instant.now();
        Integer port = findUnusedPort();
        if (port == null) {
            throw new BadRequestException("All ports are in use");
        }
        if (creationObj.users().size() > 0 && creationObj.assets().size() > 0) {
            SambaServer sambaServer = new SambaServer(null
                    , dockerConfig.mountFolder()
                    , true
                    , port//TODO parents
                    , AccessType.WRITE
                    , creationDatetime
                    , setupSharedAssets(creationObj.assets()
                            .stream().map(asset -> asset.guid())
                            .collect(Collectors.toList())
                    , creationDatetime)
                    , setupUserAccess(creationObj.users(), creationDatetime));

            sambaServer = new SambaServer(sambaServer, sambaServerService.createSambaServer(sambaServer));

            fileService.createShareFolder(sambaServer.sambaServerId());
            dockerService.startService(sambaServer);
            return new SambaInfo(sambaServer.containerPort(), "127.0.0.2", "share_" + sambaServer.sambaServerId(), sambaServer.userAccess().get(0).token(), SambaRequestStatus.OK_OPEN, null);
//            return new SambaConnection("127.0.0.2", sambaServer.containerPort(), "share_" + sambaServer.sambaServerId(), sambaServer.userAccess().get(0).token());
        } else {
            throw new BadRequestException("You have to provide users in this call");
        }
    }

    @POST
    @Path("/disconnectShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @RolesAllowed({SecurityRoles.USER, SecurityRoles.ADMIN})
    public SambaInfo disconnectSambaServer(AssetSmbRequest assetSmbRequest, @Context HttpHeaders httpHeaders, @Context SecurityContext securityContext) {
        JwtAuthenticationToken tkn = (JwtAuthenticationToken) securityContext.getUserPrincipal();
        boolean adminAction = securityContext.isUserInRole(SecurityRoles.ADMIN);
        User user = UserMapper.from(tkn);
        sambaServerService.disconnect(assetSmbRequest, user, adminAction);
//        dockerService.removeContainer(assetSmbRequest.shareName());
        return new SambaInfo(null, null, "share_1234", null, SambaRequestStatus.OK_DISCONNECTED, null);
    }

    @POST
    @Path("/closeShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public SambaInfo closeSambaServer(AssetSmbRequest assetSmbRequest, @QueryParam("syncERDA") Boolean syncERDA) {
        return new SambaInfo(null, null, "share_1234", null, SambaRequestStatus.OK_CLOSED, null);
    }

    @POST
    @Path("/openShare")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public SambaInfo openSambaServer(AssetSmbRequest assetSmbRequest) {
        return new SambaInfo(null, null, "share_1234", null, SambaRequestStatus.OK_CLOSED, null);
    }


    public List<UserAccess> setupUserAccess(List<String> users, Instant creationDateTime) {
        ArrayList<UserAccess> userAccess = new ArrayList<>();

        users.forEach(username -> {
            userAccess.add(new UserAccess(null, null, username, sambaServerService.generateRandomToken(), creationDateTime));
        });

        return userAccess;
    }

    public List<SharedAsset> setupSharedAssets(List<String> assetGuids, Instant creationDateTime) {
        ArrayList<SharedAsset> sharedAssets = new ArrayList<>();

        assetGuids.forEach(assetGuid -> {
            sharedAssets.add(new SharedAsset(null, null, assetGuid, creationDateTime));
        });

        return sharedAssets;
    }

    public Integer findUnusedPort() {
        List<Integer> usedPorts = sambaServerService.findAllUsedPorts();

        for (Integer i = dockerConfig.portRangeStart(); i < dockerConfig.portRangeEnd(); i++) {
            if (!usedPorts.contains(i)) {
                System.out.println(i);
                return i;
            }
        }

        return null;
    }

}
