package com.fa.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分页参数的边界。契约说 size 默认 20、上限 100（§6.6）。
 */
class PageQueryTest {

    @Test
    @DisplayName("缺省值：page=1 size=20")
    void defaults() {
        PageQuery q = PageQuery.of(null, null);
        assertThat(q.page()).isEqualTo(1);
        assertThat(q.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("page 小于 1 归一到 1，而不是返回空页")
    void pageLowerBound() {
        assertThat(PageQuery.of(0, 20).page()).isEqualTo(1);
        assertThat(PageQuery.of(-5, 20).page()).isEqualTo(1);
    }

    @Test
    @DisplayName("size 超过 100 被夹到 100，小于 1 被夹到 1")
    void sizeClamp() {
        assertThat(PageQuery.of(1, 500).size()).isEqualTo(100);
        assertThat(PageQuery.of(1, 0).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("offset 按 (page-1)*size 计算")
    void offset() {
        assertThat(PageQuery.of(1, 20).offset()).isZero();
        assertThat(PageQuery.of(3, 20).offset()).isEqualTo(40L);
    }
}
