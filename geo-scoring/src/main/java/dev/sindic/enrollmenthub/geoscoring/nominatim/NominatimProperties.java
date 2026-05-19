package dev.sindic.enrollmenthub.geoscoring.nominatim;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nominatim")
public record NominatimProperties(String scheme, String host, int port) {}
