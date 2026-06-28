package dev.sindic.enrollmenthub.geoscoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeocodingCacheServiceTest {

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> valueOps;

    @Mock
    JsonMapper jsonMapper;

    @InjectMocks
    GeocodingCacheService service;

    @Test
    void lookup_cacheMiss_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("some-key")).thenReturn(null);

        assertThat(service.lookup("some-key")).isEmpty();
    }

    @Test
    void lookup_cacheHit_returnsCoordinates() {
        var json = """
                {"latitude":52.52,"longitude":13.405}""";
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("some-key")).thenReturn(json);
        when(jsonMapper.readValue(json, CoordinatesPayload.class))
                .thenReturn(new CoordinatesPayload(52.52, 13.405));

        var result = service.lookup("some-key");

        assertThat(result).contains(new CoordinatesPayload(52.52, 13.405));
    }

    @Test
    void lookup_deserializationFailure_evictsAndReturnsEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("bad-key")).thenReturn("corrupted");
        when(jsonMapper.readValue("corrupted", CoordinatesPayload.class))
                .thenThrow(mock(JacksonException.class));

        var result = service.lookup("bad-key");

        assertThat(result).isEmpty();
        verify(redis).delete("bad-key");
    }

    @Test
    void store_serializesAndSetsWithTtl() {
        var coords = new CoordinatesPayload(48.8566, 2.3522);
        var ttl = Duration.ofDays(30);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(jsonMapper.writeValueAsString(coords)).thenReturn("{\"latitude\":48.8566,\"longitude\":2.3522}");

        service.store("paris-key", coords, ttl);

        verify(valueOps).set("paris-key", "{\"latitude\":48.8566,\"longitude\":2.3522}", ttl);
    }

    @Test
    void store_serializationFailure_doesNotWriteToRedis() {
        var coords = new CoordinatesPayload(0, 0);
        when(jsonMapper.writeValueAsString(coords)).thenThrow(mock(JacksonException.class));

        service.store("key", coords, Duration.ofMinutes(1));

        verifyNoInteractions(redis);
    }
}
