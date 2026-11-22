package com.fa.auth.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.auth.domain.StudentProfileRequest;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StudentProfileRequestMapper extends BaseMapper<StudentProfileRequest> {
}
