package com.fa.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据范围语义测试。
 *
 * <p>这是整个系统最值得写单测的地方：它的失败模式是「多返回了几行数据」，
 * 不会抛异常、不会报错，只会静默越权。
 */
class ScopeContextTest {

    @AfterEach
    void cleanup() {
        ScopeContext.clear();
    }

    @Test
    @DisplayName("没有上下文时读范围直接抛错，而不是默认放行")
    void noContextFailsLoudly() {
        assertThatThrownBy(ScopeContext::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@DataScope");
    }

    @Test
    @DisplayName("空授权集合 = 什么都看不到（这是最容易写错的一处）")
    void emptyScopeDeniesAll() {
        ScopeContext.set(new ScopeContext.Snapshot(false, Set.of(), Set.of()));

        assertThat(ScopeContext.current().allowsClass(1L)).isFalse();
        assertThat(ScopeContext.current().allowsCourse(1L)).isFalse();
        assertThat(ScopeContext.current().classFilter()).isEmpty();
        assertThatThrownBy(() -> ScopeContext.requireClass(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SCOPE_DENIED);
    }

    @Test
    @DisplayName("ADMIN 不受限：filter 返回 null 表示不加 where 条件")
    void adminSeesAll() {
        ScopeContext.set(ScopeContext.Snapshot.adminAll());

        assertThat(ScopeContext.current().allowsClass(999L)).isTrue();
        assertThat(ScopeContext.current().classFilter()).isNull();
        assertThat(ScopeContext.current().courseFilter()).isNull();
    }

    @Test
    @DisplayName("教师只能命中被授权的 id")
    void teacherSeesAuthorizedOnly() {
        ScopeContext.set(new ScopeContext.Snapshot(false, Set.of(1L, 2L), Set.of(10L)));

        assertThat(ScopeContext.current().allowsClass(1L)).isTrue();
        assertThat(ScopeContext.current().allowsClass(3L)).isFalse();
        assertThat(ScopeContext.current().allowsCourse(10L)).isTrue();
        assertThat(ScopeContext.current().allowsCourse(11L)).isFalse();
    }

    @Test
    @DisplayName("requireClassAndCourse 要求两条同时命中")
    void bothMustMatch() {
        ScopeContext.set(new ScopeContext.Snapshot(false, Set.of(1L), Set.of(10L)));

        ScopeContext.requireClassAndCourse(1L, 10L);

        assertThatThrownBy(() -> ScopeContext.requireClassAndCourse(1L, 11L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ScopeContext.requireClassAndCourse(2L, 10L))
                .isInstanceOf(BizException.class);
    }
}
