package com.fa.auth.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fa.auth.domain.Account;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {
}
