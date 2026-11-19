package com.keywin.mall.common.security;

import java.util.List;
import java.util.Set;

public record UserContext(
        Long userId,
        String username,
        String userType,
        Integer tokenVersion,
        List<String> roles,
        List<String> permissions
) {

    public Boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
