package com.llll.agent.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llll.agent.auth.controller.request.LoginRequest;
import com.llll.agent.auth.service.AuthService;
import com.llll.agent.user.dao.entity.UserDO;
import com.llll.agent.user.dao.mapper.UserMapper;
import com.llll.framwork.context.UserContext;
import com.llll.framwork.context.UserInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserInfo login(LoginRequest request, HttpServletRequest servletRequest) {
        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能超过 72 个 UTF-8 字节");
        }
        UserDO user = userMapper.selectOne(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, request.getUsername())
                .eq(UserDO::getDeleted, 0));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        UserInfo userInfo = UserInfo.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .build();
        HttpSession session = servletRequest.getSession(true);
        // 登录成功后更换 Session ID，保留 Session 对象但不沿用登录前的会话标识。
        servletRequest.changeSessionId();
        session.setAttribute(SESSION_USER, userInfo);
        UserContext.setUser(userInfo);
        return UserContext.getUser();
    }

    @Override
    public void logout(HttpServletRequest servletRequest) {
        try {
            HttpSession session = servletRequest.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        } catch (IllegalStateException exception) {
            // 并发退出时 Session 可能已经失效，保持退出操作可重复调用。
        } finally {
            UserContext.clear();
        }
    }
}
