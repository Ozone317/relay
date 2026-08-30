package com.example.relay.endpoint.api.validation;

import com.example.relay.endpoint.api.dto.EndpointUpdateDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EndpointUpdateValidator implements ConstraintValidator<ValidEndpointUpdate, EndpointUpdateDto> {

    @Override
    public boolean isValid(EndpointUpdateDto dto, ConstraintValidatorContext context) {
        if (dto == null) {
            return true;
        }

        return (dto.name() != null || dto.active() != null || dto.url() != null);
    }
}
