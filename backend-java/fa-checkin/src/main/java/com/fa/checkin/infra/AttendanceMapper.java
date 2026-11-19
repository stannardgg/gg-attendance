package com.fa.checkin.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.checkin.domain.Attendance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AttendanceMapper extends BaseMapper<Attendance> {
}
