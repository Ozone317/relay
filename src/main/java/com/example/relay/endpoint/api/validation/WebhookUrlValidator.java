package com.example.relay.endpoint.api.validation;

import com.example.relay.endpoint.domain.InvalidWebhookUriException;
import com.example.relay.endpoint.domain.WebhookUriParser;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class WebhookUrlValidator implements ConstraintValidator<ValidWebhookUrl, String> {

    private final WebhookUriParser parser = new WebhookUriParser();

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            parser.parse(value);
            return true;
        } catch (InvalidWebhookUriException exception) {
            return false;
        }
    }
}
