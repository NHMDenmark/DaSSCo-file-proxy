package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.configuration.DockerConfig;
import dk.northtech.dasscofileproxy.domain.AccessType;
import dk.northtech.dasscofileproxy.domain.SambaServer;
import dk.northtech.dasscofileproxy.domain.SharedAsset;
import dk.northtech.dasscofileproxy.domain.UserAccess;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;


@SpringBootTest
@Testcontainers
@DirtiesContext
public class SambaServerServiceTest {
    @Inject
    SambaServerService sambaServerService;
    @Inject
    DockerConfig dockerConfig;

    @Container
    static GenericContainer postgreSQL = new GenericContainer(DockerImageName.parse("apache/age:v1.1.0"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "dassco_file_proxy")
            .withEnv("POSTGRES_USER", "dassco_file_proxy")
            .withEnv("POSTGRES_PASSWORD", "dassco_file_proxy");

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.jdbcUrl", () -> "jdbc:postgresql://localhost:" + postgreSQL.getFirstMappedPort() + "/dassco_file_proxy");
    }


    @Test
    @Disabled
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
