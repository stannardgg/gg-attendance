package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 人脸采集「单独同意」事件（CR-028）。
 *
 * <p>只增不改的事件流：ACCEPT（同意采集）/ WITHDRAW（撤回同意）。PIPL §29 下
 * 人脸属敏感个人信息，同意必须是数据主体对当前版本告知文本的显式动作；
 * 本表是同意事实来源，{@code account.face_consent_*} 只是当前态缓存。
 *
 * <p>按凭证数据对待：不写日志内容、不进列表 DTO、不返回给前端
 * （对外只暴露版本号与时间，见 FaceStatusResp）。
 */
@Data
@TableName("face_consent_record")
public class FaceConsentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;
    private Long studentId;
    private String action;
    private String consentVersion;
    private LocalDateTime createdAt;
}
