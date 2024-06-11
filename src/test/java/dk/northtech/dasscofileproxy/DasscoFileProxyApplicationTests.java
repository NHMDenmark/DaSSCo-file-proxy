package dk.northtech.dasscofileproxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@DirtiesContext
class DasscoFileProxyApplicationTests {

    @Container
    static GenericContainer postgreSQL = new GenericContainer(DockerImageName.parse("apache/age:release_PG11_1.5.0"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "dassco_file_proxy")
            .withEnv("POSTGRES_USER", "dassco_file_proxy")
            .withEnv("POSTGRES_PASSWORD", "dassco_file_proxy");

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.jdbcUrl", () -> "jdbc:postgresql://localhost:" + postgreSQL.getFirstMappedPort() + "/dassco_file_proxy");
    }

    @Test
    void contextLoads() {
    }

}
