package com.example.relay.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The refresh cookie is ambient - the browser attaches it automatically - so the two routes that
 * trust it need a CSRF defence. A custom header is one a cross-site form post or an img tag simply
 * cannot set, and a script that tries triggers a preflight the CORS config declines. The value is
 * never inspected; only its presence matters.
 *
 * <p>This writes its own 403 rather than throwing, because GlobalExceptionHandler
 * (@RestControllerAdvice) only sees exceptions raised inside controllers - filters run before
 * DispatcherServlet. Throwing here would escape to Tomcat's /error dispatch instead.
 */
@Component
public class CsrfHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Relay-Auth";

    private static final Set<String> COOKIE_AUTHENTICATED_PATHS =
            Set.of("/api/v1/auth/refresh", "/api/v1/auth/logout");

    @Override
    public void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (COOKIE_AUTHENTICATED_PATHS.contains(request.getRequestURI())
                && request.getHeader(HEADER_NAME) == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":403,\"message\":\"Missing " + HEADER_NAME + " header\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
