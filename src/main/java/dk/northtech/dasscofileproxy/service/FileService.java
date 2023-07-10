package dk.northtech.dasscofileproxy.service;

import com.google.common.base.Strings;
import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class FileService {
    DockerConfig dockerConfig;


    @Inject
    public FileService(DockerConfig dockerConfig) {
        this.dockerConfig = dockerConfig;
    }

    public String createShareFolder(Long shareId) {
        System.out.println(dockerConfig.mountFolder() + shareId);
        File newDirectory = new File(dockerConfig.mountFolder() + "share_" + shareId);
        newDirectory.mkdirs();
        return newDirectory.getPath();
    }


    public void removeShareFolder(Long shareId) {
        System.out.println(dockerConfig.mountFolder() + shareId);
        if (Strings.isNullOrEmpty(dockerConfig.mountFolder())) {
            throw new RuntimeException("Cannot delete share folder, mountFolder is null");
        }
        Path path = Path.of(dockerConfig.mountFolder() + "share_" + shareId);
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .peek(System.out::println)
                    .forEach(File::delete);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
//        File[] allFiles = newDirectory.listFiles();
//        if (allFiles != null) {
//            for (File file : allFiles) {
//                if (!file.isDirectory()) {
//                    file.delete();
//                }
//            }
//        }
//        return newDirectory.delete();
    }
}
