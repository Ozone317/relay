package com.example.relay.endpoint.api.validation;

import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.endpoint.domain.InvalidWebhookUriException;
import com.example.relay.endpoint.domain.ParsedWebhookUri;
import com.example.relay.endpoint.domain.WebhookUriParser;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class WebhookUrlValidator implements ConstraintValidator<ValidWebhookUrl, String> {

    private final WebhookUriParser parser;
    private final PublicDestinationAddressPolicy addressPolicy;

    public WebhookUrlValidator() {
        this(new PublicDestinationAddressPolicy());
    }

    public WebhookUrlValidator(PublicDestinationAddressPolicy addressPolicy) {
        this.parser = new WebhookUriParser();
        this.addressPolicy = addressPolicy;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            ParsedWebhookUri parsed = parser.parse(value);
            return !parsed.isLiteral() || addressPolicy.evaluate(parsed.literalAddress().addressBytes()).allowed();
        } catch (InvalidWebhookUriException exception) {
            return false;
        }
    }
}
