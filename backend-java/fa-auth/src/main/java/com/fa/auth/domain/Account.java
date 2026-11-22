package com.fa.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 统一认证主体。
 *
 * <p>★ 注意这里<b>没有</b> adminSingleton 字段：它是数据库生成列，
 * 专门用于 uk_single_admin 唯一约束，业务代码不得读写
 * （写了 insert 会报 "generated column cannot be assigned"）。
 */
@Data
@TableName("account")
public class Account {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt strength=10，长度 60 */
    private String passwordHash;

    /** ADMIN / TEACHER / STUDENT */
    private String role;

    /** ACTIVE / DISABLED */
    private String status;

    /**
     * 凭据版本。改密或改登录名时 +1，令牌里的 {@code cv} 与之不符即失效。
     *
     * <p>放在数据库而不是 Redis：密码变了就该立刻生效，不能因为缓存故障
     * 让旧令牌继续跑到过期。代价只是一次已在发生的账号查询里多读一列。
     */
    private Integer credentialVersion;

    /** 人脸采集「单独同意」的时间（§13.4），未同意为 null */
    private LocalDateTime faceConsentAt;

    private String faceConsentVersion;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
