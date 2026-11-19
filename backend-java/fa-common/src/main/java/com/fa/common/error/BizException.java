package com.fa.common.error;

import java.util.Map;

/**
 * 业务异常。Service 层只抛这个，不抛 RuntimeException——
 * 全局异常处理器据此映射 HTTP 状态与 code，避免 500 满天飞。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.details = Map.of();
    }

    /** 覆盖默认文案，用于给出更精确的提示（如「只检测到 1 次，要求 2 次」）。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = Map.of();
    }

    /** 附带结构化细节，写进响应 data（如 FA-2005 的 studentCount、FA-4003 的 hint）。 */
    public BizException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static BizException of(ErrorCode code) {
        return new BizException(code);
    }
}
