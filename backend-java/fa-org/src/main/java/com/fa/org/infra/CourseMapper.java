package com.fa.org.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.org.domain.Course;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {
}
