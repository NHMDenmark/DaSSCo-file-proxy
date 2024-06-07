package dk.northtech.dasscofileproxy.service;

import com.jcraft.jsch.*;
import dk.northtech.dasscofileproxy.configuration.SFTPConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@Testcontainers
@Disabled
class SftpServiceTest {

    @Inject
    SFTPService sftpService;
    @Inject
    SFTPConfig sftpConfig;

    @Inject
    ErdaDataSource erdaDataSource;
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
    public void testListFiles() {
        try {
            Files.createDirectories(Path.of("target/test/subfolder/"));
            Files.write(Path.of("target/test/test.txt"), "asdf".getBytes());
            Files.write(Path.of("target/test/subfolder/test.txt"), "asdf".getBytes());
            new FileService(null, null, null).deleteAll(new File("target/test"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // This test creates more ERDAClients than ERDA allows. This will cause application running on the same computer to lose all ERDA sessions.
    // The purpose of this is to test how the file-proxy recovers from ERDA downtime errors
    @Test
    @Disabled
    public void forceERDAError() {
        try {
            // Open an SFTP channel
            String localFile = "target/sample.txt";
            String remoteDir = "TestInstitution/test-collection/testAsset_2/file_1.txt";

            ERDAClient erdaClient = new ERDAClient(sftpConfig);
            try {

                new ERDAClient(sftpConfig).putFileToPath(localFile, remoteDir);
                System.out.println("1");
                new ERDAClient(sftpConfig).putFileToPath(localFile, remoteDir);
                System.out.println("2");
                new ERDAClient(sftpConfig).putFileToPath(localFile, remoteDir);
                System.out.println("3");
                new ERDAClient(sftpConfig).putFileToPath(localFile, remoteDir);
                System.out.println("4");
            } catch (Exception e) {
                System.out.println("Failed");
            }
            erdaClient.putFileToPath(localFile, remoteDir);
            System.out.printf("It worked");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Disabled
    public void eurdahTezt() {
        try {
            // Open an SFTP channel
//            String localFile = "target/sample.txt";
//            String remoteDir = "TestInstitution/test-collection/testAsset_2/file_1.txt";
//            ErdaDataSource erdaDataSource = new ErdaDataSource(3, true, sftpConfig);
//            CompletableFuture.runAsync(() -> {
//                try {
//                    try {
//                        ERDAClient acquire = erdaDataSource.acquire();
//                    } catch (Exception e) {
//                        throw new RuntimeException(e);
//                    }
//                }
//            });
//            acquire.putFileToPath(localFile, remoteDir);
//            ERDAClient erdaClient = new ERDAClient(sftpConfig);

            for (int i = 0; i < 100; i++) {
                try {
                    Files.write(Path.of("target/test/test." + i + ".txt"), ("asdf'-" + i).getBytes());
                } catch (IOException e) {
                    fail(e);
                }
            }
//            ERDAClient acquire = erdaDataSource.acquire();
            List<String> files2Delete = new ArrayList<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                final int i2 = i;
                CompletableFuture<Void> voidCompletableFuture = CompletableFuture.runAsync(() -> {
                    String localFile = "target/test/test." + i2 + ".txt";
                    String remoteDir = "TestInstitution/test-collection/testAsset_2/test." + i2 + ".txt";
                    files2Delete.add(remoteDir);

                    try (ERDAClient erdaClient = erdaDataSource.acquire();){
                        erdaClient.putFileToPath(localFile, remoteDir);
//                        acquire.deleteFiles();
                        System.out.println("done " + i2);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                    futures.add(voidCompletableFuture);
//                erdaClient.putFileToPath(localFile, remoteDir);
//                System.out.println(i);
            }
            futures.forEach(CompletableFuture::join);
            try (ERDAClient erdaClient = erdaDataSource.acquire();){
                erdaClient.deleteFiles(files2Delete);
            }
//                erdaClient.putFileToPath(localFile, remoteDir);
//                System.out.println(i);

//            erdaDataSource.recycle(acquire);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Disabled
    public void eurdahTezt2() {
        try {
            // Open an SFTP channel
//            String localFile = "target/sample.txt";
//            String remoteDir = "TestInstitution/test-collection/testAsset_2/file_1.txt";
//            ErdaDataSource erdaDataSource = new ErdaDataSource(3, true, sftpConfig);
//            CompletableFuture.runAsync(() -> {
//                try {
//                    try {
//                        ERDAClient acquire = erdaDataSource.acquire();
//                    } catch (Exception e) {
//                        throw new RuntimeException(e);
//                    }
//                }
//            });
//            acquire.putFileToPath(localFile, remoteDir);
//            ERDAClient erdaClient = new ERDAClient(sftpConfig);

//            for (int i = 0; i < 100; i++) {
//                try {
//                    Files.write(Path.of("target/test/test." + i + ".txt"), ("asdf'-" + i).getBytes());
//                } catch (IOException e) {
//                    fail(e);
//                }
//            }
            List<String> files2Delete = new ArrayList<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final int i2 = i;
                CompletableFuture<Void> voidCompletableFuture = CompletableFuture.runAsync(() -> {
                    String localFile = "C:\\Users\\Thomas\\Documents\\big-test-files\\pic-" + i2 + ".tif";
                    String remoteDir = "TestInstitution/test-collection/testAsset_2/test." + i2 + ".tif";
                    files2Delete.add(remoteDir);

                    try (ERDAClient acquire = erdaDataSource.acquire();) {
                        acquire.putFileToPath(localFile, remoteDir);
                        System.out.println("done " + i2);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                futures.add(voidCompletableFuture);

            }
            futures.forEach(CompletableFuture::join);
            try (ERDAClient acquire = erdaDataSource.acquire();) {
//                        acquire.putFileToPath(localFile, remoteDir);
//                        acquire.deleteFiles();
            acquire.deleteFiles(files2Delete);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }


        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Disabled
    public void listFiles() {
        try {
            String remoteDir = "TestInstitution/test-collection/testAsset_2";

            Collection<String> files = new ERDAClient(sftpConfig).listFiles(remoteDir);

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
            String remoteDir = "TestInstitution/test-collection/testAsset_2/file_1.txt";

            new ERDAClient(sftpConfig).downloadFile(remoteDir, localFile);
        } catch (SftpException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Disabled
    public void testExists() {
        try {
            String localFile = "target/sample.txt";
            String remoteDir = "/test-institution/test-collection/a4";
            boolean exists = new ERDAClient(sftpConfig).exists(remoteDir, true);
            System.out.println(exists);
            assertThat(exists).isTrue();
        } catch (SftpException | IOException e) {
            throw new RuntimeException(e);
        }
    }

}
