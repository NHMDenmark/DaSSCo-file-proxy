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
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;


@SpringBootTest
public class SambaServerServiceTest {
    @Inject
    SambaServerService sambaServerService;
    @Inject
    DockerConfig dockerConfig;


    @Test
    public void test () {
        SambaServer testObject = new SambaServer(null, "/here", true, 6060
                , AccessType.WRITE, Instant.now(), List.of(new SharedAsset(null, null
                , "guid", Instant.now())), List.of(new UserAccess(null, null
                , "grand", "token", Instant.now())));
        sambaServerService.createSambaServer(testObject);
    }

    @Test
    public void randomToken() {
        String first = sambaServerService.generateRandomToken();
        String second = sambaServerService.generateRandomToken();

        assertThat(first.equals(second)).isFalse();
    }

    @Test
    public void findUnusedPort() {
        List<Integer> usedPorts = sambaServerService.findAllUsedPorts();

        for (Integer i = dockerConfig.portRangeStart(); i <= dockerConfig.portRangeEnd(); i++) {
            if (!usedPorts.contains(i)) {
                System.out.println(i);
            }
        }
    }

}
