package com.llll.agent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llll.agent.user.dao.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
