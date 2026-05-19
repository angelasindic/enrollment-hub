package dev.sindic.enrollmenthub.geoscoring;

import dev.sindic.enrollmenthub.geoscoring.libpostal.LibpostalClient;
import dev.sindic.enrollmenthub.geoscoring.service.GeoIndexProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(GeoIndexProperties.class)
@EnableScheduling
public class Application implements ApplicationRunner {

    private final LibpostalClient libpostalClient;

    public Application(LibpostalClient libpostalClient) {
        this.libpostalClient = libpostalClient;
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var result = libpostalClient.parse("Laan van Meerdervoort 900, Den Haag");
        System.out.println(result);
    }
}