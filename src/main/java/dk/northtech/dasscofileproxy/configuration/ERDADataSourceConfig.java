package dk.northtech.dasscofileproxy.configuration;

import dk.northtech.dasscofileproxy.service.ErdaDataSource;
import dk.northtech.dasscofileproxy.service.ErdaDataSource2;
import jakarta.inject.Inject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ERDADataSourceConfig {
    private StorageConfig storageConfig;

    @Inject
    public ERDADataSourceConfig(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }
    
    @Bean
    public ErdaDataSource erdaDataSource() {
        return new ErdaDataSource( true, storageConfig);
    }

    @Bean
    public ErdaDataSource2 erdaDataSource2() {
        return new ErdaDataSource2( storageConfig);
    }
}
