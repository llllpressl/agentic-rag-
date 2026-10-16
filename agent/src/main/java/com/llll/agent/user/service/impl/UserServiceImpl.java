package com.llll.agent.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llll.agent.user.controller.request.UserCreateRequest;
import com.llll.agent.user.dao.entity.UserDO;
import com.llll.agent.user.dao.mapper.UserMapper;
import com.llll.agent.user.service.UserService;
import com.llll.framwork.context.UserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserInfo createUser(UserCreateRequest request) {
        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能超过 72 个 UTF-8 字节");
        }
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, request.getUsername()));
        if (userCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        UserDO user = UserDO.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("user")
                .deleted(0)
                .build();
        try {
            if (userMapper.insert(user) != 1) {
                throw new IllegalStateException("用户创建失败");
            }
        } catch (DuplicateKeyException exception) {
            // 预查询之后仍可能并发创建同名用户，最终以数据库唯一约束为准。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        // 创建用户不改变当前登录身份，也不自动登录。
        return UserInfo.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }
}
