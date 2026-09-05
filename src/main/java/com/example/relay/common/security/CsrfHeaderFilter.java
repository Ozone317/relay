package com.example.relay.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
 *
 * <p>The guarded paths are matched with {@link PathPatternRequestMatcher} - the same decoded-path
 * matching engine {@code authorizeHttpRequests} uses - rather than a raw string comparison against
 * {@link HttpServletRequest#getRequestURI()}. The raw URI is percent-encoded and undecoded, while
 * Spring routes on the decoded path, so a request to {@code /api/v1/auth/logou%74} ({@code %74} is
 * {@code t}) would not equal the literal string {@code "/api/v1/auth/logout"} yet would still be
 * dispatched to the logout controller - bypassing this filter entirely. Matching on the same
 * decoded representation Spring routes on closes that gap.
 */
@Component
public class CsrfHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Relay-Auth";

    private static final RequestMatcher COOKIE_AUTHENTICATED_PATHS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/logout"));

    @Override
    public void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (COOKIE_AUTHENTICATED_PATHS.matches(request) && request.getHeader(HEADER_NAME) == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":403,\"message\":\"Missing " + HEADER_NAME + " header\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
