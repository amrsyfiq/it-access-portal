package com.itaccess.portal.config;

import com.itaccess.portal.security.GatewayJwtAuthenticationFilter;
import com.itaccess.portal.security.GatewayJwtVerifier;
import com.itaccess.portal.security.RequestAttributeUserResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;

/**
 * Active with portal.auth.mode=gateway-jwt (the "gateway" profile): identity comes from the JWT the
 * WSO2 gateway signs, instead of the development-only X-Demo-* headers (see WebConfig).
 */
@Configuration
@ConditionalOnProperty(name = "portal.auth.mode", havingValue = "gateway-jwt")
public class GatewaySecurityConfig implements WebMvcConfigurer {

    @Bean
    GatewayJwtVerifier gatewayJwtVerifier(@Value("${portal.gateway.jwt.certificate}") Resource certificate,
                                          @Value("${portal.gateway.jwt.issuer}") String issuer,
                                          Clock clock) throws IOException, CertificateException {
        return new GatewayJwtVerifier(readPublicKey(certificate), issuer, clock);
    }

    @Bean
    FilterRegistrationBean<GatewayJwtAuthenticationFilter> gatewayJwtFilter(GatewayJwtVerifier verifier,
                                                                            JsonMapper jsonMapper) {
        FilterRegistrationBean<GatewayJwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new GatewayJwtAuthenticationFilter(verifier, jsonMapper));
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new RequestAttributeUserResolver());
    }

    /** WSO2's signing certificate, exported by infra/wso2/setup.mjs (PEM). Only its public key is used. */
    private static RSAPublicKey readPublicKey(Resource certificate) throws IOException, CertificateException {
        if (!certificate.exists()) {
            throw new IllegalStateException("WSO2 signing certificate not found at " + certificate
                    + ". Run: node infra/wso2/setup.mjs");
        }
        try (InputStream in = certificate.getInputStream()) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return (RSAPublicKey) cert.getPublicKey();
        }
    }
}
