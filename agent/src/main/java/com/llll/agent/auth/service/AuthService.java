package com.llll.agent.auth.service;

import com.llll.agent.auth.controller.request.LoginRequest;
import com.llll.framwork.context.UserInfo;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {

    String SESSION_USER = "agent.session.user";

    UserInfo login(LoginRequest request, HttpServletRequest servletRequest);

    void logout(HttpServletRequest servletRequest);
}
