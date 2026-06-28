package dev.sindic.enrollmenthub.geoscoring.libpostal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableConfigurationProperties(LibpostalProperties.class)
public class LibpostalConfig {

    @Bean
    RestClient libpostalRestClient(LibpostalProperties props) {
        var requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(props.connectTimeout())
                        .withReadTimeout(props.readTimeout()));
        return RestClient.builder()
                .baseUrl(UriComponentsBuilder.newInstance()
                        .scheme("http")
                        .host(props.host())
                        .port(props.port())
                        .build()
                        .toUri())
                .requestFactory(requestFactory)
                .build();
    }
}
