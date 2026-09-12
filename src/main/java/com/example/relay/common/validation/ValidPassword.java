package com.example.relay.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Relay's one password-length rule, shared so registration and password reset can never quietly drift apart. Composed
 * over {@link Size} rather than a custom validator - there is no logic here beyond the length check Bean Validation's
 * own {@code @Size} already implements correctly.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Size(min = 8, message = "password must be at least 8 characters long")
@ReportAsSingleViolation
public @interface ValidPassword {

    String message() default "password must be at least 8 characters long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
