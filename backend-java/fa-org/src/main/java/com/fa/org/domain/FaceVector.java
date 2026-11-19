package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 人脸向量（只写不读；Python 通过内部接口全量快照消费）。
 *
 * <p>embedding 存 float32 小端序、已 L2 归一化的 512 维向量，写入前由 face 服务算好，
 * Java 只做搬运与归属校验，不碰模型（CR-009）。
 *
 * <p>按敏感数据对待：不写日志、不进 DTO、不返回给任何前端。
 */
@Data
@TableName("face_vector")
public class FaceVector {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;
    private String modelName;
    private Integer dim;
    private byte[] embedding;
    private BigDecimal qualityScore;
    private BigDecimal yaw;
    private BigDecimal pitch;
    private BigDecimal brightness;
    private String sourceRef;
    private LocalDateTime createdAt;
}
