package com.fa.common.api;

/**
 * 分页参数。契约：page 从 1 起，size 默认 20、上限 100（项目文档 §6.6）。
 *
 * <p>越界不做静默修正而是直接夹紧——静默修正会让"我传了 500 却只拿到 100 条"
 * 这种事在排查时看不出痕迹。
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 1) {
            page = 1;
        }
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(
                page == null ? 1 : page,
                size == null ? DEFAULT_SIZE : size);
    }

    public long offset() {
        return (long) (page - 1) * size;
    }

    /** MyBatis-Plus 的 limit 参数需要 int。 */
    public int limit() {
        return size;
    }
}
