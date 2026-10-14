package com.itaccess.portal.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Gateway mode: hands controllers the CurrentUser that GatewayJwtAuthenticationFilter verified. */
public class RequestAttributeUserResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object user = webRequest.getAttribute(GatewayJwtAuthenticationFilter.CURRENT_USER_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (user == null) {
            // Cannot happen for /api/** (the filter rejects first); guards against misconfiguration.
            throw new InvalidGatewayTokenException("No verified gateway user on this request");
        }
        return (CurrentUser) user;
    }
}
