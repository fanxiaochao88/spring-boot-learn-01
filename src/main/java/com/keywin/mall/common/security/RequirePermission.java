package com.keywin.mall.common.security;

import java.lang.annotation.*;

@Documented
@Target({ElementType.METHOD, ElementType.TYPE}) // 可以用于方法也可以用于类
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限码 */
    String[] value();

    /** 多个权限之间如何处理 */
    Logical logical() default Logical.AND;

    enum Logical {
        AND, OR
    }
}
