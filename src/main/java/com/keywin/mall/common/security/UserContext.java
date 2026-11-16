package com.keywin.mall.common.security;

public record UserContext(
        Long userId,
        String username,
        String userType,
        Integer tokenVersion
) {
}
