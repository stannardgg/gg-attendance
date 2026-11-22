package com.fa.auth.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.auth.domain.TeacherProfileRequest;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TeacherProfileRequestMapper extends BaseMapper<TeacherProfileRequest> {
}
