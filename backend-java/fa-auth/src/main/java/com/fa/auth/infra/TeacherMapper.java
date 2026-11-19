package com.fa.auth.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.auth.domain.Teacher;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TeacherMapper extends BaseMapper<Teacher> {
}
