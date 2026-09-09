package com.example.relay.delivery.exception;

import java.util.Set;

public class InvalidSortPropertyException extends RuntimeException {

    public InvalidSortPropertyException(String property, Set<String> legalProperties) {
        super("Invalid sort property '" + property + "'; legal sortable fields are: "
                + legalProperties.stream().sorted().toList());
    }
}
