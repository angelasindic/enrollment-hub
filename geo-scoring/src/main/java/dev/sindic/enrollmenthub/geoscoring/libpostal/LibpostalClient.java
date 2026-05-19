package dev.sindic.enrollmenthub.geoscoring.libpostal;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class LibpostalClient {

    private final RestClient libpostalRestClient;

    public LibpostalClient(RestClient libpostalRestClient) {
        this.libpostalRestClient = libpostalRestClient;
    }

    public List<AddressComponent> parse(String address) {
        return libpostalRestClient.get()
                .uri("/parse?address={address}", address)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}


