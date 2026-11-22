package com.fa.common.error;

import org.springframework.http.HttpStatus;

/**
 * 错误码表（锁定）。唯一事实来源：contracts/enums/error-codes.json 与项目文档 §6.3。
 *
 * <p>前端只按 code 分支，不解析 message 文案。所以这里的 message 是兜底展示用的，
 * 需要更精确的提示时由业务层抛 {@link BizException#BizException(ErrorCode, String)} 覆盖。
 *
 * <p>新增码必须走 CR——号段是契约的一部分，私自扩展会让前端的分支逻辑失效。
 */
public enum ErrorCode {

    OK("FA-0000", HttpStatus.OK, "ok"),

    // ---- 1xxx 认证 ----
    BAD_CREDENTIALS("FA-1001", HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    TOKEN_INVALID("FA-1002", HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),
    FORBIDDEN("FA-1003", HttpStatus.FORBIDDEN, "权限不足"),
    ACCOUNT_EXISTS("FA-1004", HttpStatus.CONFLICT, "账号已存在"),
    ADMIN_CREATION_DENIED("FA-1005", HttpStatus.FORBIDDEN, "不允许注册管理员账号"),

    // ---- 2xxx 组织 ----
    NOT_FOUND("FA-2001", HttpStatus.NOT_FOUND, "资源不存在"),
    CODE_DUPLICATED("FA-2002", HttpStatus.CONFLICT, "编码重复"),
    CLASS_DUPLICATED("FA-2003", HttpStatus.CONFLICT, "班级码或班级名称重复"),
    ORG_ARCHIVED("FA-2004", HttpStatus.BAD_REQUEST, "该班级或课程已归档，不能发起签到"),
    CLASS_HAS_STUDENTS("FA-2005", HttpStatus.CONFLICT, "班级下仍有在读学生，请先转出或归档"),
    SCOPE_DENIED("FA-2006", HttpStatus.FORBIDDEN, "你未被授权该班级或该课程"),
    PROFILE_REQUEST_CONFLICT("FA-2007", HttpStatus.CONFLICT, "资料修改申请状态冲突"),

    // ---- 3xxx 签到 ----
    SESSION_CLOSED("FA-3001", HttpStatus.CONFLICT, "签到会话已结束"),
    QR_INVALID("FA-3002", HttpStatus.BAD_REQUEST, "二维码已失效，请重新扫码"),
    ALREADY_CHECKED_IN("FA-3003", HttpStatus.CONFLICT, "你已完成签到，无需重复"),
    NOT_IN_CLASS("FA-3004", HttpStatus.FORBIDDEN, "你不在本次签到的班级中"),
    RETRY_EXCEEDED("FA-3005", HttpStatus.TOO_MANY_REQUESTS, "重试次数过多，请稍后再试或提交申诉"),

    // ---- 4xxx 人脸 ----
    NO_FACE("FA-4001", HttpStatus.BAD_REQUEST, "未检测到人脸"),
    MULTI_FACE("FA-4002", HttpStatus.BAD_REQUEST, "只能留下一个人进行签到"),
    ACTION_INCOMPLETE("FA-4003", HttpStatus.BAD_REQUEST, "动作未完成"),
    LIVENESS_FAILED("FA-4004", HttpStatus.BAD_REQUEST, "活体检测未通过"),
    FACE_MISMATCH("FA-4005", HttpStatus.BAD_REQUEST, "人脸不匹配"),
    FACE_NOT_ACTIVE("FA-4006", HttpStatus.BAD_REQUEST, "人脸档案未通过审核"),
    IDENTITY_MISMATCH("FA-4007", HttpStatus.BAD_REQUEST, "身份校验未通过，请本人操作"),
    FACE_ALREADY_ENROLLED("FA-4008", HttpStatus.CONFLICT, "这张人脸已用于其他学生档案"),

    // ---- 5xxx 申诉 ----
    APPEAL_EXISTS("FA-5001", HttpStatus.CONFLICT, "该记录已有待处理申诉"),
    APPEAL_OUT_OF_SCOPE("FA-5002", HttpStatus.FORBIDDEN, "该申诉不在你的处理范围内"),

    // ---- 6xxx Agent ----
    NEED_CONFIRM("FA-6001", HttpStatus.CONFLICT, "该操作需人工确认，已返回预览与确认令牌"),
    CONFIRM_EXPIRED("FA-6002", HttpStatus.BAD_REQUEST, "确认令牌已过期，请重新发起"),
    PARAMS_CHANGED("FA-6003", HttpStatus.CONFLICT, "参数已变更，需重新确认"),
    AGENT_SCOPE_DENIED("FA-6004", HttpStatus.FORBIDDEN, "超出当前授权范围"),
    UNDO_UNSUPPORTED("FA-6005", HttpStatus.BAD_REQUEST, "该操作不支持撤销，需人工处理"),

    // ---- 9xxx 系统 ----
    INTERNAL_ERROR("FA-9001", HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    FACE_SERVICE_DOWN("FA-9002", HttpStatus.SERVICE_UNAVAILABLE, "人脸服务暂不可用");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(String code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
