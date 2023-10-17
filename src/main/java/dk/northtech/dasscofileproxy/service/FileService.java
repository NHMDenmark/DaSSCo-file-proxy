package dk.northtech.dasscofileproxy.service;

import com.google.common.base.Strings;
import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

@Service
public class FileService {
    DockerConfig dockerConfig;


    @Inject
    public FileService(DockerConfig dockerConfig) {
        this.dockerConfig = dockerConfig;
    }

    public String createShareFolder(Long shareId) {
        System.out.println(dockerConfig.mountFolder() + shareId);
        File newDirectory = new File( "/volume/share_" + shareId);
        if(!newDirectory.exists()){
            newDirectory.mkdirs();
        }
        return newDirectory.getPath();
    }


    public void removeShareFolder(Long shareId) {
        if (Strings.isNullOrEmpty(dockerConfig.mountFolder())) {
            throw new RuntimeException("Cannot delete share folder, mountFolder is null");
        }
        Path path = Path.of(dockerConfig.mountFolder() + "share_" + shareId);
        deleteAll(path.toFile());
    }

    public void listfiles(File directory, List<File> files) {
        File[] fList = directory.listFiles();
        if(fList != null)
            for (File file : fList) {
                if (file.isFile()) {
                    files.add(file);
                } else if (file.isDirectory()) {
                    listfiles(new File(file.getAbsolutePath()), files);
                }
            }
    }

    void deleteAll(File dir) {
        for (File file: dir.listFiles()) {
            if (file.isDirectory())
                deleteAll(file);
            file.delete();
        }
        dir.delete();
    }
}
