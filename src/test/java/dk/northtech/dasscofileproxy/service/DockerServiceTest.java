package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.domain.AccessType;
import dk.northtech.dasscofileproxy.domain.SambaServer;
import dk.northtech.dasscofileproxy.domain.SharedAsset;
import dk.northtech.dasscofileproxy.domain.UserAccess;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

@SpringBootTest
public class DockerServiceTest {
    @Inject
    DockerService dockerService;
    @Inject
    DockerConfig dockerConfig;


    @Test
    public void createSambaShare () {
        SambaServer sambaServer = new SambaServer(null, dockerConfig.mountFolder() + "/share_16", true, 6060
                , AccessType.WRITE, Instant.now(), List.of(new SharedAsset(null, null
                , "guid", Instant.now())), List.of(new UserAccess(null, null
                , "grand", "token", Instant.now())));
        dockerService.startService(sambaServer);

        assertThat(dockerService.listServices("share_").size() > 0).isTrue();

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        dockerService.removeContainer("share_" + sambaServer.sambaServerId());
    }
}
