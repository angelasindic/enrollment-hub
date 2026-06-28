package dev.sindic.enrollmenthub.geoscoring.nominatim;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Configuration
@EnableConfigurationProperties(NominatimProperties.class)
public class NominatimConfig {

    @Bean
    RestClient nominatimRestClient(NominatimProperties props) {
        var requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(props.connectTimeout())
                        .withReadTimeout(props.readTimeout()));
        return RestClient.builder()
                .baseUrl(UriComponentsBuilder.newInstance()
                        .scheme(props.scheme())
                        .host(props.host())
                        .port(props.port())
                        .build()
                        .toUri())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    log.debug("Nominatim → {} {}", request.getMethod(), request.getURI());
                    var response = execution.execute(request, body);
                    log.debug("Nominatim ← {}", response.getStatusCode());
                    return response;
                })
                .build();
    }
}
