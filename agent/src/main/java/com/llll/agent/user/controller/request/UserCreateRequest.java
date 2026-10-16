package com.llll.agent.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class UserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_][A-Za-z0-9_.-]{2,63}$",
            message = "用户名需为 3 至 64 位字母、数字、下划线、点或短横线，首位不能为点或短横线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 72, message = "密码长度需为 6 至 72 个字符")
    @ToString.Exclude
    private String password;
}
