package dk.northtech.dasscofileproxy.service;

import dk.northtech.dasscofileproxy.domain.DasscoFile;
import dk.northtech.dasscofileproxy.repository.FileRepository;
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

import java.util.List;

import static com.google.common.truth.Truth.assertThat;


@SpringBootTest
@Testcontainers
@DirtiesContext
@Disabled
public class FileServiceTest {
    @Inject
    FileService fileService;
;

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

//    @Test
//    @Disabled
//    public void test () {
//        assertThat(fileService.createShareFolder(16L)).isNull();
//    }

    @Test
    public void testListFilesByGuid() {
        fileService.jdbi.withHandle(h-> {
            FileRepository attach = h.attach(FileRepository.class);
            attach.insertDirectory(new DasscoFile(null, "a1", "/file.jpg",13245L, 123));
            attach.insertDirectory(new DasscoFile(null, "a2","/test/tezt.txt" ,13245L, 123));
            attach.insertDirectory(new DasscoFile(null, "a2","/test/tezt2.txt" ,10000L, 12332));
            return h;
        }).close();
        List<DasscoFile> result = fileService.listFilesByAssetGuid("a1");
        assertThat(result.size()).isEqualTo(1);
        DasscoFile dasscoFile = result.get(0);
        assertThat(dasscoFile.assetGuid()).isEqualTo("a1");
        assertThat(dasscoFile.path()).isEqualTo("/file.jpg");
        assertThat(dasscoFile.sizeBytes()).isEqualTo(13245L);
        assertThat(dasscoFile.crc()).isEqualTo(123);
        assertThat(dasscoFile.fileId()).isNotNull();
        assertThat(dasscoFile.fileId()).isGreaterThan(0);
        fileService.jdbi.withHandle(h-> {
            FileRepository attach = h.attach(FileRepository.class);
            attach.deleteFilesByAssetGuid("a1");
            return h;
        }).close();
        List<DasscoFile> res2 = fileService.listFilesByAssetGuid("a1");
        assertThat(res2).hasSize(0);
        List<DasscoFile> res3 = fileService.listFilesByAssetGuid("a2");
        assertThat(res3).hasSize(2);
        fileService.jdbi.withHandle(h-> {
            FileRepository attach = h.attach(FileRepository.class);
            assertThat(attach.getTotalAllocatedByAsset("a2")).isEqualTo(23245L);
            return h;
        }).close();
    }

}
