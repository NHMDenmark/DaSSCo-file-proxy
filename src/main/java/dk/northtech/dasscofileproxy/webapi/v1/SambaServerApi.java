package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.domain.*;
import dk.northtech.dasscofileproxy.service.DockerService;
import dk.northtech.dasscofileproxy.service.FileService;
import dk.northtech.dasscofileproxy.service.SambaServerService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    public SambaConnection createSambaServer(CreationObj creationObj) {
        Instant creationDatetime = Instant.now();
        Integer port = findUnusedPort();

        if (port == null) {
            throw new BadRequestException("All ports are in use");
        }

        if (creationObj.users().size() > 0 && creationObj.assetGuids().size() > 0) {

            SambaServer sambaServer = new SambaServer(null, dockerConfig.mountFolder(), true, port
                    , AccessType.WRITE, creationDatetime, setupSharedAssets(creationObj.assetGuids(), creationDatetime)
                    , setupUserAccess(creationObj.users(), creationDatetime));

            sambaServer = new SambaServer(sambaServer, sambaServerService.createSambaServer(sambaServer));

            fileService.createShareFolder(sambaServer.sambaServerId());

            dockerService.startService(sambaServer);

            return new SambaConnection("127.0.0.2", sambaServer.containerPort(), "share_" + sambaServer.sambaServerId(), sambaServer.userAccess().get(0).token());

        } else {
            throw new BadRequestException("You have to provide users in this call");
        }
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
