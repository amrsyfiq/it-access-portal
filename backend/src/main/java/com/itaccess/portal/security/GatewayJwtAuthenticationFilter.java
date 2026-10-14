package com.itaccess.portal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Gateway mode only. Runs before every /api/** request (except the public health check) and lets
 * it through only with a valid X-JWT-Assertion from WSO2. The resulting CurrentUser is stored on the
 * request for controllers (see RequestAttributeUserResolver).
 *
 * A filter rather than a controller argument, so that endpoints which do not need the user
 * (GET /api/requests, ...) are protected too.
 */
public class GatewayJwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-JWT-Assertion";
    public static final String CURRENT_USER_ATTRIBUTE = CurrentUser.class.getName();

    private static final Logger log = LoggerFactory.getLogger(GatewayJwtAuthenticationFilter.class);

    private final GatewayJwtVerifier verifier;
    private final JsonMapper jsonMapper;

    public GatewayJwtAuthenticationFilter(GatewayJwtVerifier verifier, JsonMapper jsonMapper) {
        this.verifier = verifier;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            reject(request, response, "Missing " + HEADER + ": call this API through the WSO2 gateway");
            return;
        }
        try {
            request.setAttribute(CURRENT_USER_ATTRIBUTE, verifier.verify(token));
        } catch (InvalidGatewayTokenException ex) {
            reject(request, response, ex.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        log.warn("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Unauthorized");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
