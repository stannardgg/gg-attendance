package com.fa.common.web;

import com.fa.common.api.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一把 traceId 塞进响应包。
 *
 * <p>放在 advice 而不是让每个 Controller 自己传：Controller 少一个参数，
 * 也就少一个忘记的地方；而且 traceId 是基础设施信息，不该污染业务签名。
 */
@RestControllerAdvice(basePackages = "com.fa")
public class ResultTraceAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return Result.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result<?> r) {
            return new Result<>(r.code(), r.message(), r.data(), TraceContext.currentTraceId());
        }
        return body;
    }
}
