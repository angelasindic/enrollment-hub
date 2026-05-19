package dev.sindic.enrollmenthub.geoscoring.libpostal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableConfigurationProperties(LibpostalProperties.class)
public class LibpostalConfig {

    @Bean
    RestClient libpostalRestClient(LibpostalProperties props) {
        return RestClient.builder()
                .baseUrl(UriComponentsBuilder.newInstance()
                        .scheme("http")
                        .host(props.host())
                        .port(props.port())
                        .build()
                        .toUri())
                .build();
    }
}