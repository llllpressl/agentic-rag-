package com.llll.agent.auth.controller;

import com.llll.agent.auth.controller.request.LoginRequest;
import com.llll.agent.auth.service.AuthService;
import com.llll.framwork.context.UserInfo;
import com.llll.framwork.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<UserInfo> login(@Valid @RequestBody LoginRequest request,
                                  HttpServletRequest servletRequest) {
        return Result.success(authService.login(request, servletRequest));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest servletRequest) {
        authService.logout(servletRequest);
        return Result.success();
    }
}
