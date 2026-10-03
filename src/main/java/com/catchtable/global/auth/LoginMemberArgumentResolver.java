package com.catchtable.global.auth;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String MEMBER_ID_HEADER="X-Member-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter)
    {
        return parameter.hasParameterAnnotation(LoginMember.class)
                && parameter.getParameterType().equals(Long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(MEMBER_ID_HEADER);
        if (header == null || header.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return Long.valueOf(header);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

    }

}
