package com.llll.agent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user")
public class UserDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String username;

    @ToString.Exclude
    private String password;

    private String role;

    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
}
