package com.llll.framwork.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 当前线程的用户上下文，只保存身份信息，不负责认证。
 * 使用后必须在 finally 中调用 clear()，避免线程复用时残留用户信息。
 * 线程池传递需要使用 TTL 包装任务或线程池，仅声明此容器不会自动完成传递。
 */
public final class UserContext {

    private static final TransmittableThreadLocal<UserInfo> USER_HOLDER =
            new TransmittableThreadLocal<>();

    private UserContext() {
    }

    public static void setUser(UserInfo user) {
        if (user == null) {
            clear();
            return;
        }
        USER_HOLDER.set(user);
    }

    public static UserInfo getUser() {
        return USER_HOLDER.get();
    }

    public static String getUserId() {
        UserInfo user = getUser();
        return user == null ? null : user.getUserId();
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}
