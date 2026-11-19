package com.fa.checkin.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.checkin.domain.CheckinSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMapper extends BaseMapper<CheckinSession> {
}
