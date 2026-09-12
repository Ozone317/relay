package com.example.relay.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Single seam for client-IP extraction. Today this is a direct-connection deployment, so {@code getRemoteAddr()} is
 * accurate; if Relay ever sits behind a reverse proxy, switching to parsing a trusted {@code X-Forwarded-For} header is
 * a one-class change here, not a rewrite of every caller.
 */
@Component
public class ClientIpResolver {

    public String resolve(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
