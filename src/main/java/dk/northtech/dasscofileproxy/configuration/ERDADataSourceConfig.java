package dk.northtech.dasscofileproxy.configuration;

import dk.northtech.dasscofileproxy.service.ErdaDataSource;
import jakarta.inject.Inject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ERDADataSourceConfig {
    private SFTPConfig sftpConfig;

    @Inject
    public ERDADataSourceConfig(SFTPConfig sftpConfig) {
        this.sftpConfig = sftpConfig;
    }
    
    @Bean
    public ErdaDataSource erdaDataSource() {
        return new ErdaDataSource(3, true, sftpConfig);
    }
}
