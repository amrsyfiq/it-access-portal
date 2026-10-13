package com.itaccess.portal.config;

import com.itaccess.portal.security.DemoHeaderUserResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Active while portal.auth.mode=demo-headers (the default until WSO2 is integrated). */
@Configuration
@ConditionalOnProperty(name = "portal.auth.mode", havingValue = "demo-headers", matchIfMissing = true)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new DemoHeaderUserResolver());
    }
}
