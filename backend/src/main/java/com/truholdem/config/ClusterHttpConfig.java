package com.truholdem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for node-to-node cluster command forwarding (Phase 5). Short timeouts so a slow/dead
 * owner fails fast and the caller can re-claim the table rather than hang on the action.
 */
@Configuration
public class ClusterHttpConfig {

    @Bean
    public RestClient clusterRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
