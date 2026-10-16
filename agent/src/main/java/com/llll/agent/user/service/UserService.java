package com.llll.agent.user.service;

import com.llll.agent.user.controller.request.UserCreateRequest;
import com.llll.framwork.context.UserInfo;

public interface UserService {

    UserInfo createUser(UserCreateRequest request);
}
