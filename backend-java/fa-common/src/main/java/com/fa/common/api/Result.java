package com.fa.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 统一响应包。契约见 contracts/openapi/java-api.yaml 的 Result。
 *
 * <p>为什么 data 用泛型而不是 Object：Controller 返回类型能直接表达语义，
 * 前端 codegen 出来的类型也更准。序列化时由 Jackson 处理。
 */
public record Result<T>(String code, String message, T data, String traceId) {

    public static final String OK = "FA-0000";

    public static <T> Result<T> ok(T data) {
        return new Result<>(OK, "ok", data, null);
    }

    public static Result<Void> ok() {
        return new Result<>(OK, "ok", null, null);
    }

    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(code, message, null, null);
    }

    public static <T> Result<T> fail(String code, String message, T data) {
        return new Result<>(code, message, data, null);
    }

    @JsonIgnore
    public boolean isOk() {
        return OK.equals(code);
    }
}
