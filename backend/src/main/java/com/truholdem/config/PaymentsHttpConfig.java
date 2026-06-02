package com.truholdem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** HTTP client for the crypto payment gateway adapter (sane connect/read timeouts so a slow gateway fails fast). */
@Configuration
public class PaymentsHttpConfig {

    @Bean
    public RestClient paymentsRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
