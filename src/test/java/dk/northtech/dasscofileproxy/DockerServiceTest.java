package dk.northtech.dasscofileproxy;

import dk.northtech.dasscofileproxy.service.DockerService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static com.google.common.truth.Truth.assertThat;

@SpringBootTest
public class DockerServiceTest {
    @Inject
    DockerService dockerService;


    @Test
    public void test () {
        dockerService.startContainer();

        assertThat(dockerService.listContainers().size() > 0).isTrue();

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        dockerService.removeContainer();
    }
}
