package dev.sindic.enrollmenthub.geoscoring.nominatim;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "nominatim")
public record NominatimProperties(
        String scheme,
        String host,
        int port,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {}
