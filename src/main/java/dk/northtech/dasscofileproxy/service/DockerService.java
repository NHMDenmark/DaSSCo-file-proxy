package dk.northtech.dasscofileproxy.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.*;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DockerService {
    DockerClient dockerClient;

    @Inject
    public DockerService(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public void startContainer() {
        ArrayList<String> environments = new ArrayList<>();
        ArrayList<PortConfig> ports = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();
        ArrayList<Mount> volumes = new ArrayList<>();

        environments.add("TZ=Europe/Copenhagen");
        environments.add("USERID=0");
        environments.add("GROUPID=0");

        ports.add(new PortConfig()
                .withProtocol(PortConfigProtocol.TCP)
                .withPublishedPort(6060)
                .withTargetPort(445)
                .withPublishMode(PortConfig.PublishMode.ingress)
        );

        args.add("-u");
        args.add("refinery1;badpass");
        args.add("-s");
        args.add("share;/share;no;no;no;refinery1;none");

        volumes.add(new Mount()
                .withType(MountType.BIND)
                .withSource("/host_mnt/Users/christofferhansen/dev/baseImage/jfileserver/test_folder")
                .withTarget("/share")
        );

        dockerClient.createServiceCmd(new ServiceSpec()
                .withName("samba")
                .withTaskTemplate(new TaskSpec()
                        .withResources(new ResourceRequirements()
                                .withLimits(new ResourceSpecs()
                                        .withNanoCPUs(100000000)
                                )
                        )
                        .withContainerSpec(new ContainerSpec()
                                .withImage("dperson/samba:latest")
                                .withEnv(environments)
                                .withArgs(args)
                                .withMounts(volumes)
                        )
                )
                .withEndpointSpec(new EndpointSpec()
                        .withPorts(ports)
                )
        ).exec();
    }

    public List<com.github.dockerjava.api.model.Service> listContainers() {
        return dockerClient.listServicesCmd()
                .withNameFilter(Lists.newArrayList("samba"))
                .exec();
    }

    public void removeContainer() {
        dockerClient.removeServiceCmd("samba").exec();
    }
}
