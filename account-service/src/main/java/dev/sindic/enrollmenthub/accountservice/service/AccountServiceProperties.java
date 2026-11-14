package dev.sindic.enrollmenthub.accountservice.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param dedupCapacity how many recent {@code decisionId}s the duplicate filter remembers. A
 *                      redelivery older than this window is handled again. Default: 10000.
 */
@ConfigurationProperties(prefix = "account-service")
public record AccountServiceProperties(@DefaultValue("10000") int dedupCapacity) {
}
