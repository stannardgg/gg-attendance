package com.fa.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * <p>{@code scanBasePackages = "com.fa"}：各业务模块的包根是 com.fa.xxx，
 * 而启动类在 com.fa.bootstrap 下，默认扫描范围只覆盖子包，必须显式放大。
 *
 * <p>Mapper 扫描写全限定包而不是 {@code com.fa}：后者会把所有接口都当 Mapper 注册，
 * 将来只要有任何一个接口（比如某个 Port/SPI）落在 com.fa 下就会启动失败。
 */
@SpringBootApplication(scanBasePackages = "com.fa")
@MapperScan({"com.fa.auth.infra", "com.fa.org.infra"})
@EnableScheduling
public class FaceAttendanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FaceAttendanceApplication.class, args);
    }
}
