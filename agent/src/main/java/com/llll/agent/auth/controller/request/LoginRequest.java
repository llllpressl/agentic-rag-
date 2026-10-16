package com.llll.agent.auth.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名不能超过 64 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 72, message = "密码不能超过 72 个字符")
    @ToString.Exclude
    private String password;
}
