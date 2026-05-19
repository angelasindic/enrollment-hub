package dev.sindic.enrollmenthub.geoscoring.nominatim;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
        return RestClient.builder()
                .baseUrl(UriComponentsBuilder.newInstance()
                        .scheme(props.scheme())
                        .host(props.host())
                        .port(props.port())
                        .build()
                        .toUri())
                .requestInterceptor((request, body, execution) -> {
                    log.info("Nominatim → {} {}", request.getMethod(), request.getURI());
                    var response = execution.execute(request, body);
                    log.info("Nominatim ← {}", response.getStatusCode());
                    return response;
                })
                .build();
    }
}
