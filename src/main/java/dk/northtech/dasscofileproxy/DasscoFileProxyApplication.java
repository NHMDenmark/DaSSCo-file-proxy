package dk.northtech.dasscofileproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@ServletComponentScan
@EnableScheduling
public class DasscoFileProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(DasscoFileProxyApplication.class, args);
    }

}
