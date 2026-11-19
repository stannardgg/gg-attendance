package com.fa.checkin.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.checkin.domain.Appeal;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppealMapper extends BaseMapper<Appeal> {
}
