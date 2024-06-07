package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.assets.ErdaProperties;
import dk.northtech.dasscofileproxy.service.FtpsService;
import jakarta.inject.Inject;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@DirtiesContext // The database container is torn down between tests, so the pool cannot be reused
class FtpsServiceTest {

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
    @Inject
    private FtpsService ftpsService;

    @Mock
    private FTPSClient ftpsClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void close_shouldLogoutAndDisconnectFromFtpServer() throws IOException {
        // Arrange
        ftpsService.setFtpsClient(ftpsClient);

        // Act
        ftpsService.close();

        // Assert
        verify(ftpsClient).logout();
        verify(ftpsClient).disconnect();
    }

    @Test
    void listFiles_shouldReturnFileNames() throws IOException {
        // Arrange
        FTPFile[] files = new FTPFile[2];
        files[0] = new FTPFile();
        files[0].setName("file1.txt");
        files[1] = new FTPFile();
        files[1].setName("file2.txt");

        when(ftpsClient.listFiles("/path")).thenReturn(files);

        ftpsService.setFtpsClient(ftpsClient);

        // Act
        Collection<String> result = ftpsService.listFiles("/path");

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.contains("file1.txt"));
        assertTrue(result.contains("file2.txt"));
    }

    @Test
    void makeDirectory_shouldCreateDirectory() throws IOException {
        // Arrange
        when(ftpsClient.makeDirectory("/path")).thenReturn(true);

        ftpsService.setFtpsClient(ftpsClient);

        // Act
        boolean result = ftpsService.makeDirectory("/path");

        // Assert
        assertTrue(result);
        verify(ftpsClient).makeDirectory("/path");
    }

    @Test
    void getFiles_shouldReturnFiles() throws IOException {
        // Arrange
        FTPFile[] files = new FTPFile[2];
        files[0] = new FTPFile();
        files[0].setName("file1.txt");
        files[1] = new FTPFile();
        files[1].setName("file2.txt");

        when(ftpsClient.listFiles("/path")).thenReturn(files);

        ftpsService.setFtpsClient(ftpsClient);

        // Act
        FTPFile[] result = ftpsService.getFiles("/path");

        // Assert
        assertEquals(2, result.length);
        assertEquals("file1.txt", result[0].getName());
        assertEquals("file2.txt", result[1].getName());
    }

    @Test
    void exists_shouldReturnTrueWhenFileExists() throws IOException {
        // Arrange
        FTPFile[] files = new FTPFile[1];
        files[0] = new FTPFile();
        files[0].setName("file.txt");

        when(ftpsClient.listFiles("/path")).thenReturn(files);

        ftpsService.setFtpsClient(ftpsClient);

        // Act
        boolean result = ftpsService.exists("/path");

        // Assert
        assertTrue(result);
        verify(ftpsClient).listFiles("/path");
    }

    @Test
    void exists_shouldReturnFalseWhenFileDoesNotExist() throws IOException {
        // Arrange
        when(ftpsClient.listFiles("/path")).thenReturn(null);

        ftpsService.setFtpsClient(ftpsClient);

        // Act
        boolean result = ftpsService.exists("/path");

        // Assert
        assertFalse(result);
        verify(ftpsClient).listFiles("/path");
    }
}
