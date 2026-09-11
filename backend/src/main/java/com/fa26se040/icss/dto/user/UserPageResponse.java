package com.fa26se040.icss.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPageResponse {
    private Page<UserListResponse> users;
    private long normalCount;
    private long systemCount;
}
