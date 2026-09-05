package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
public class CsrfHeaderFilterTest {

    @Mock
    private FilterChain filterChain;

    private CsrfHeaderFilter underTest;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        underTest = new CsrfHeaderFilter();
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void rejectsRefresh_whenTheHeaderIsAbsent() throws Exception {
        underTest.doFilterInternal(request("/api/v1/auth/refresh"), response, filterChain);

        assertEquals(403, response.getStatus());
        verify(filterChain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsLogout_whenTheHeaderIsAbsent() throws Exception {
        underTest.doFilterInternal(request("/api/v1/auth/logout"), response, filterChain);

        assertEquals(403, response.getStatus());
    }

    @Test
    void allowsRefresh_whenTheHeaderIsPresent_regardlessOfItsValue() throws Exception {
        MockHttpServletRequest request = request("/api/v1/auth/refresh");
        request.addHeader("X-Relay-Auth", "anything-at-all");

        underTest.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void ignoresEveryOtherRoute() throws Exception {
        MockHttpServletRequest request = request("/api/v1/auth/login");

        underTest.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void rejectsRefresh_whenThePathIsPercentEncodedAndTheHeaderIsAbsent() throws Exception {
        // "refre%73h" decodes to "refresh" - a raw-string comparison against getRequestURI()
        // would miss this, letting the request slip past the filter to the controller.
        underTest.doFilterInternal(request("/api/v1/auth/refre%73h"), response, filterChain);

        assertEquals(403, response.getStatus());
        verify(filterChain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsLogout_whenThePathIsPercentEncodedAndTheHeaderIsAbsent() throws Exception {
        // "logou%74" decodes to "logout".
        underTest.doFilterInternal(request("/api/v1/auth/logou%74"), response, filterChain);

        assertEquals(403, response.getStatus());
    }

    @Test
    void allowsRefresh_whenThePathIsPercentEncodedAndTheHeaderIsPresent() throws Exception {
        MockHttpServletRequest request = request("/api/v1/auth/refre%73h");
        request.addHeader("X-Relay-Auth", "anything-at-all");

        underTest.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
