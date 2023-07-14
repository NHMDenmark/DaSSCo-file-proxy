package dk.northtech.dasscofileproxy.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.*;
import com.google.common.collect.Lists;
import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.domain.SambaServer;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class DockerService {
    DockerClient dockerClient;
    DockerConfig dockerConfig;

    @Inject
    public DockerService(DockerClient dockerClient, DockerConfig dockerConfig) {
        this.dockerClient = dockerClient;
        this.dockerConfig = dockerConfig;
    }

    public boolean startService(SambaServer sambaServer) {
        ArrayList<String> environments = new ArrayList<>();
        ArrayList<PortConfig> ports = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();
        ArrayList<Mount> volumes = new ArrayList<>();

        environments.add("TZ=Europe/Copenhagen");
        environments.add("USERID=0");
        environments.add("GROUPID=0");

        ports.add(new PortConfig()
                .withProtocol(PortConfigProtocol.TCP)
                .withPublishedPort(sambaServer.containerPort())
                .withTargetPort(445)
                .withPublishMode(PortConfig.PublishMode.host)
        );

        StringBuilder sb = new StringBuilder();
        sb.append("share_").append(sambaServer.sambaServerId()).append(";/share;no;no;no;");
        sambaServer.userAccess().forEach(userAccess -> {
            args.add("-u");
            args.add(String.format("%1$s;%2$s", userAccess.username(), userAccess.token()));
            sb.append(userAccess.username());
            sb.append(",");
        });
        sb.deleteCharAt(sb.length() - 1);
        sb.append(";none");
        args.add("-s");
        args.add(sb.toString());

        volumes.add(new Mount()
                .withType(MountType.BIND)
                .withSource(dockerConfig.mountFolder() + "share_" + sambaServer.sambaServerId())
                .withTarget("/share")
        );

        dockerClient.createServiceCmd(new ServiceSpec()
                .withName("share_" + sambaServer.sambaServerId())
                .withTaskTemplate(new TaskSpec()
                        .withResources(
                                new ResourceRequirements()
                                .withLimits(new ResourceSpecs()
                                        .withNanoCPUs(500000000)
                                        .withMemoryBytes(250000000)
                                )
                        )
                        .withPlacement(new ServicePlacement().withConstraints(List.of("node.hostname == " + dockerConfig.nodeHost())))
                        .withContainerSpec(new ContainerSpec()
                                .withImage("dperson/samba:latest")
                                .withEnv(environments)
                                .withArgs(args)
                                .withMounts(volumes)
//                                .withTty(true)
//                                .withOpenStdin(true)
                        )
                )
                .withEndpointSpec(new EndpointSpec()
                        .withPorts(ports)
                )
        ).exec();

        return listServices("share_").size() > 0;
    }

    public List<com.github.dockerjava.api.model.Service> listServices(String serviceName) {
        return dockerClient.listServicesCmd()
                .withNameFilter(Lists.newArrayList(serviceName))
                .exec();
    }

    public boolean removeContainer(String serviceName) {
        List<com.github.dockerjava.api.model.Service> services = listServices(serviceName);
        if(services.size() > 0) {
            dockerClient.removeServiceCmd(serviceName).exec();
            return true;
        }
        return false;

    }
}
