package dk.northtech.dasscofileproxy.service;

import com.jcraft.jsch.*;
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

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@DirtiesContext
@Disabled
class SftpServiceTest {

    @Inject
    SFTPService sftpService;

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
    public void uploadFile() {
        try {
            // Open an SFTP channel
            String localFile = "target/sample.txt";
            String remoteDir = "DaSSCoStorage/TestInstitution/test-collection/testAsset_2/file_1.txt";

            sftpService.putFileToPath(localFile, remoteDir);
        } catch (JSchException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Disabled
    public void listFiles() {
        try {
            String remoteDir = "DaSSCoStorage/TestInstitution/test-collection/testAsset_2";

            Collection<String> files = sftpService.listFiles(remoteDir);

            files.forEach(System.out::println);

        } catch (SftpException | JSchException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Disabled
    public void downloadFile() {
        try {
            String localFile = "target/sample.txt";
            String remoteDir = "DaSSCoStorage/TestInstitution/test-collection/testAsset_2/file_1.txt";

            sftpService.downloadFile(remoteDir, localFile);
        } catch (SftpException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}