package dk.northtech.dasscofileproxy.webapi.v1;

import dk.northtech.dasscofileproxy.webapi.model.FileUploadData;
import org.junit.jupiter.api.Test;

import static com.google.common.truth.Truth.assertThat;

class FileUploadDataTest {
    @Test
    void createPath() {
        FileUploadData minimalAsset = new FileUploadData("a1",  "i1", "c1", "hej.txt", 12);
        assertThat(minimalAsset.getFilePath()).isEqualTo("/666/i1/c1/a1/hej.txt");
        assertThat(minimalAsset.getFilePath()).isEqualTo("/i1/c1/a1/hej.txt");
        assertThat(minimalAsset.getFilePath()).isEqualTo("/i1/c1/a1/subfolder/hej.txt");
    }
}