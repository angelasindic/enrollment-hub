package dev.sindic.enrollmenthub.geoscoring.libpostal;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "libpostal")
public record LibpostalProperties(String host, int port) {}
