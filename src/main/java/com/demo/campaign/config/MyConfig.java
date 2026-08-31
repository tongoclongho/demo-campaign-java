package com.demo.campaign.config;

import com.demo.campaign.service.CacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@Slf4j
public class MyConfig {

    @Bean
    public AuditorAware<String> auditorAwareRef() {
        return () -> Optional.of("ADMIN");
    }

    /**
     * Fulfill redis cache on application startup
     *
     * @param cacheManager
     * @return
     */
    @Bean
    public ApplicationRunner startupRunner(CacheManager cacheManager) {
        return args -> {
            log.info("Fulfill redis cache on application startup");
            cacheManager.refreshRedisCache();
        };
    }

}
