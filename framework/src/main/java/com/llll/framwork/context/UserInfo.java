package com.llll.framwork.context;

import lombok.Builder;

/**
 * 当前用户的基础信息。字段不可变，避免异步任务共享引用时修改用户身份。
 */
public final class UserInfo {

    private final String userId;
    private final String username;

    @Builder
    public UserInfo(String userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }
}
