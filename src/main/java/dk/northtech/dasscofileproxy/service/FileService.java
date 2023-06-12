package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class FileService {
    DockerConfig dockerConfig;


    @Inject
    public FileService(DockerConfig dockerConfig) {
        this.dockerConfig = dockerConfig;
    }

    public boolean createShareFolder(Long shareId) {
        System.out.println(dockerConfig.mountFolder() + shareId);
        File newDirectory = new File(dockerConfig.mountFolder() + "share_" + shareId);
        return newDirectory.mkdirs();
    }
}
