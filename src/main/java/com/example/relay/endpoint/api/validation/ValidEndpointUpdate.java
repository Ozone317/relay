package com.example.relay.endpoint.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EndpointUpdateValidator.class)
public @interface ValidEndpointUpdate {

    String message() default "At least one field must be provided";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
