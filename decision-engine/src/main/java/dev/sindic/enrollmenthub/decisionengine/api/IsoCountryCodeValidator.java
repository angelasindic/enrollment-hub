package dev.sindic.enrollmenthub.decisionengine.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Locale;
import java.util.Set;

public class IsoCountryCodeValidator implements ConstraintValidator<IsoCountryCode, String> {

    private static final Set<String> ASSIGNED_CODES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || ASSIGNED_CODES.contains(value);
    }
}
