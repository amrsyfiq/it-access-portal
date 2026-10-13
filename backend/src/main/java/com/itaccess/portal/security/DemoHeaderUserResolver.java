package com.itaccess.portal.security;

import org.springframework.core.MethodParameter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * DEVELOPMENT ONLY - NOT SECURITY.
 * Builds the CurrentUser from the X-Demo-User / X-Demo-Role headers sent by the Angular "demo user"
 * switcher. Anyone can send these headers. Phase 4 replaces this with identity taken from the
 * WSO2 gateway's signed JWT.
 */
public class DemoHeaderUserResolver implements HandlerMethodArgumentResolver {

    public static final String USER_HEADER = "X-Demo-User";
    public static final String ROLE_HEADER = "X-Demo-Role";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String username = webRequest.getHeader(USER_HEADER);
        String role = webRequest.getHeader(ROLE_HEADER);
        return new CurrentUser(
                StringUtils.hasText(username) ? username.trim() : "anonymous",
                "ADMIN".equalsIgnoreCase(role) ? Role.ADMIN : Role.EMPLOYEE);
    }
}
