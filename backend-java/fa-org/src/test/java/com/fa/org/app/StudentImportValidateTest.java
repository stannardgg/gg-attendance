package com.fa.org.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 导入的逐行校验。抽出成静态方法就是为了能这样直接测，
 * 不用起 Spring 上下文、不用连库。
 */
class StudentImportValidateTest {

    @Test
    @DisplayName("合法行通过")
    void ok() {
        assertThat(StudentService.validate("S2024001", "张三", Set.of())).isNull();
    }

    @Test
    @DisplayName("学号为空 / 姓名分别为空都要给出具体原因")
    void blanks() {
        assertThat(StudentService.validate("", "张三", Set.of())).isEqualTo("学号为空");
        assertThat(StudentService.validate("S1", " ", Set.of())).isEqualTo("姓名为空");
        assertThat(StudentService.validate(null, "张三", Set.of())).isEqualTo("学号为空");
    }

    @Test
    @DisplayName("超长字段被拒")
    void tooLong() {
        assertThat(StudentService.validate("S".repeat(33), "张三", Set.of()))
                .isEqualTo("学号超过 32 位");
        assertThat(StudentService.validate("S1", "名".repeat(65), Set.of()))
                .isEqualTo("姓名超过 64 位");
    }

    @Test
    @DisplayName("同一文件内重复学号被拒（DB 唯一键之前先挡住，报错更好懂）")
    void duplicateInFile() {
        Set<String> seen = new HashSet<>();
        seen.add("S2024001");
        assertThat(StudentService.validate("S2024001", "张三", seen))
                .isEqualTo("学号在文件内重复");
    }

    @Test
    @DisplayName("边界长度刚好通过：学号 32、姓名 64")
    void boundary() {
        assertThat(StudentService.validate("S".repeat(32), "名".repeat(64), Set.of())).isNull();
    }
}
