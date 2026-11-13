package dev.sindic.enrollmenthub.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Fails startup when an OAuth2 client registration has no usable secret.
 * <p>
 * The secrets are bound from {@code ${ENROLLMENT_LOGIN_CLIENT_SECRET}} and
 * {@code ${PAYMENT_CHECK_CLIENT_SECRET}}, which have no default. Configuration-property binding does
 * not fail on an unresolvable placeholder; it keeps the literal text, so without this check a gateway
 * started without the variables boots normally and every login fails at the token endpoint with
 * {@code invalid_client}. The check runs against the registrations rather than the variables, so a
 * secret supplied through any property source passes.
 */
@Component
class ClientSecretsCheck {

    private static final Logger log = LoggerFactory.getLogger(ClientSecretsCheck.class);

    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)}");

    ClientSecretsCheck(ClientRegistrationRepository clientRegistrations) {
        if (!(clientRegistrations instanceof Iterable<?> registrations)) {
            throw new IllegalStateException("Cannot enumerate client registrations of "
                    + clientRegistrations.getClass().getName() + " to check their secrets");
        }
        List<String> problems = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Object element : registrations) {
            ClientRegistration registration = (ClientRegistration) element;
            ids.add(registration.getRegistrationId());
            problem(registration).ifPresent(problems::add);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("OAuth2 client secrets missing: " + String.join("; ", problems));
        }
        log.info("OAuth2 client registrations configured: {}", ids);
    }

    private static Optional<String> problem(ClientRegistration registration) {
        if (ClientAuthenticationMethod.NONE.equals(registration.getClientAuthenticationMethod())) {
            return Optional.empty();
        }
        String secret = registration.getClientSecret();
        String id = registration.getRegistrationId();
        if (!StringUtils.hasText(secret)) {
            return Optional.of("registration '" + id + "' has a blank client-secret");
        }
        var placeholder = UNRESOLVED_PLACEHOLDER.matcher(secret);
        if (placeholder.find()) {
            return Optional.of("registration '" + id + "' client-secret is unresolved; set "
                    + placeholder.group(1));
        }
        return Optional.empty();
    }
}
