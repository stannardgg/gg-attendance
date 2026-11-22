package com.fa.auth.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.auth.domain.Teacher;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TeacherMapper extends BaseMapper<Teacher> {

    /**
     * 显式写入院系，允许 {@code null}。
     *
     * <p>为什么不直接 {@code updateById}：MyBatis-Plus 的更新策略默认跳过 null 字段，
     * 「清空院系」时实体里是 null，SQL 里根本不会出现这一列，于是永远清不掉。
     * 清空必须走一条显式 SET 的语句。
     */
    @Update("UPDATE teacher SET department = #{department} WHERE id = #{id}")
    int updateDepartment(@Param("id") long id, @Param("department") String department);

    /** 同 {@link #updateDepartment}，职称的清空也要显式写 null。 */
    @Update("UPDATE teacher SET title = #{title} WHERE id = #{id}")
    int updateTitle(@Param("id") long id, @Param("title") String title);
}
