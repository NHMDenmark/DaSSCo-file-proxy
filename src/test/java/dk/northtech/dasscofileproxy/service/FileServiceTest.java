package dk.northtech.dasscofileproxy.service;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static com.google.common.truth.Truth.assertThat;


@SpringBootTest
public class FileServiceTest {
    @Inject
    FileService fileService;

    @Test
    public void test () {
        assertThat(fileService.createShareFolder(16L)).isTrue();
    }
}
