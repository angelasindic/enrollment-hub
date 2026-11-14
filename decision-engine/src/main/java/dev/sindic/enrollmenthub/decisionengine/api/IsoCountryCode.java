package dev.sindic.enrollmenthub.decisionengine.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value is an officially assigned ISO 3166-1 alpha-2 country code in upper case.
 * {@code null} is valid; combine with {@code @NotNull} for a required field.
 *
 * <p>User-assigned codes are rejected, among them {@code XK}, which several registries use for
 * Kosovo.
 */
@Documented
@Constraint(validatedBy = IsoCountryCodeValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR,
        ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IsoCountryCode {

    String message() default "must be an ISO 3166-1 alpha-2 country code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
