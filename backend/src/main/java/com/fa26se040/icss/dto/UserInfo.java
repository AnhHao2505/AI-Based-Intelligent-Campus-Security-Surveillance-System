package com.fa26se040.icss.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserInfo {
    private Integer id;
    private String fullName;
    private String email;
    private String role;
    private String userCode;
}
