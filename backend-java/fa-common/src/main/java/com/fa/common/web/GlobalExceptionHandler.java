package com.fa.common.web;

import com.fa.common.api.Result;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常映射。
 *
 * <p>一条容易被忽略的约定：<b>参数校验失败沿用 FA-2001</b>。
 * 契约里的 2xxx 号段原本只有「资源不存在」，但项目文档 §6.4 自己就说了
 * 「POST /admin/sessions 缺 classId 或 courseId → FA-2001」——即缺参数走 FA-2001。
 * 校验失败属于同一类，复用同一个码，避免凭空造号段（造了前端也不认）。
 * 已在 docs/handover/WP-02.md 记录为「契约软点」，建议下一个大版本补一个 FA-2xxx 校验码。
 */
@RestControllerAdvice(basePackages = "com.fa")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBiz(BizException e) {
        ErrorCode code = e.errorCode();
        Map<String, Object> data = e.details().isEmpty() ? null : new LinkedHashMap<>(e.details());
        // 业务异常是「预期内的失败」，用 warn 级别，不要把日志刷成 error
        log.warn("biz-error code={} message={}", code.code(), e.getMessage());
        return ResponseEntity.status(code.httpStatus())
                .body(Result.fail(code.code(), e.getMessage(), data));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result<Object>> handleValidation(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraint(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return badRequest("缺少参数 " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("参数 " + e.getName() + " 类型不正确");
    }

    /**
     * 未知字段、JSON 格式错误。
     *
     * <p>这条是场景 16 的关键：给课程 DTO 传 classId 时，Spring 会因为
     * fail-on-unknown-properties=true 抛这里，服务端明确拒绝，而不是静默忽略。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Object>> handleUnreadable(HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause().getMessage();
        return badRequest("请求体不合法" + (detail == null ? "" : "：" + detail));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Object>> handleDenied(AccessDeniedException e) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code.code(), code.message()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Object>> handleAuth(AuthenticationException e) {
        ErrorCode code = ErrorCode.TOKEN_INVALID;
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code.code(), code.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleOther(Exception e) {
        // 这里必须打 error 且带堆栈，否则线上只能看到一句「服务内部错误」
        log.error("unhandled-exception", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code.code(), code.message()));
    }

    private ResponseEntity<Result<Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.NOT_FOUND.code(), message));
    }
}
