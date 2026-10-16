package com.llll.agent.auth.filter;

import com.llll.agent.auth.service.AuthService;
import com.llll.framwork.context.UserContext;
import com.llll.framwork.context.UserInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 只同步 Session 身份到 UserContext，不做登录拦截或 user/admin 角色过滤。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SessionUserContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UserContext.clear();
        try {
            UserContext.setUser(getSessionUser(request));
            filterChain.doFilter(request, response);
        } finally {
            // SSE 后台任务已经显式保存 userId，不依赖请求线程上的上下文继续运行。
            UserContext.clear();
        }
    }

    private UserInfo getSessionUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        try {
            Object sessionUser = session.getAttribute(AuthService.SESSION_USER);
            if (sessionUser instanceof UserInfo userInfo) {
                return userInfo;
            }
            return null;
        } catch (IllegalStateException exception) {
            // 同一浏览器的另一个请求可能刚完成退出，将当前请求按未登录处理。
            return null;
        }
    }
}
