package com.frauddetection.client.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Spring bean configuration.
 *
 * <p>Configures a {@link RestTemplate} with connect and read timeouts from properties.
 * Jackson's snake_case naming strategy is set via application.yml
 * (spring.jackson.property-naming-strategy=SNAKE_CASE), so no custom ObjectMapper bean
 * is needed here — Spring Boot's JacksonAutoConfiguration handles it.
 */
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate(FraudApiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
