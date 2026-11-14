package dev.sindic.enrollmenthub.gateway;

import dev.sindic.enrollmenthub.gateway.SecurityConfiguration.LoggingLoginFailureHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingLoginFailureHandlerTest {

    @Test
    void loggable_replacesEveryCharacterThatCanEndALogLine() {
        // CR, LF, tab, ESC, NEL (C1), line separator, paragraph separator
        assertThat(LoggingLoginFailureHandler.loggable("a\rb\nc\td\u001Be\u0085f g h"))
                .isEqualTo("a_b_c_d_e_f_g_h");
    }

    @Test
    void loggable_keepsOrdinaryText() {
        assertThat(LoggingLoginFailureHandler.loggable("invalid_client: Zugriff verweigert (401)"))
                .isEqualTo("invalid_client: Zugriff verweigert (401)");
    }

    @Test
    void loggable_cutsLongValues() {
        String logged = LoggingLoginFailureHandler.loggable("x".repeat(5000));

        assertThat(logged).hasSize(LoggingLoginFailureHandler.MAX_LOGGED_LENGTH + 3).endsWith("...");
    }

    @Test
    void loggable_passesNullThrough() {
        assertThat(LoggingLoginFailureHandler.loggable(null)).isNull();
    }
}
