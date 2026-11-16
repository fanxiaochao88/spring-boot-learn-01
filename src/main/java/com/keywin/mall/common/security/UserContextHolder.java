package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.BusinessException;

/**
 * ThreadLocal就相当于key, 和业务value组成一个map的Entry,
 * 放入ThreadLocalMap, 每个线程都有这样一个map
 */

public final class UserContextHolder {
    private static final ThreadLocal<UserContext> userContext = new ThreadLocal<>();

    public static void setUserContext(UserContext context) {
        userContext.set(context);
    }

    public static UserContext getUserContext() {
        UserContext context = userContext.get();

        if (context == null) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        return context;
    }

    public static UserContext getOrNull() {
        return userContext.get();
    }

    public static void clear() {
        userContext.remove();
    }
}
