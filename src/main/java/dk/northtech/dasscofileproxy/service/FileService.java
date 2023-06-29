package dk.northtech.dasscofileproxy.service;

import com.google.common.base.Strings;
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

    public boolean removeShareFolder(Long shareId) {
        System.out.println(dockerConfig.mountFolder() + shareId);
        if (Strings.isNullOrEmpty(dockerConfig.mountFolder())) {
            throw new RuntimeException("Cannot delete share folder, mountFolder is null");
        }
        File newDirectory = new File(dockerConfig.mountFolder() + "share_" + shareId);
        File[] allFiles = newDirectory.listFiles();
        if (allFiles != null) {
            for (File file : allFiles) {
                if (!file.isDirectory()) {
                    file.delete();
                }
            }
        }
        return newDirectory.delete();
    }
}
