package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.ForbiddenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
@Order(1)
public class PermissionAspect {


    @Around("@annotation(com.keywin.mall.common.security.RequirePermission) "
            + "|| @within(com.keywin.mall.common.security.RequirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();

        // 先在方法上找注解
        RequirePermission annotation = AnnotatedElementUtils.findMergedAnnotation(
                signature.getMethod(), RequirePermission.class
        );
        if (annotation == null) {
            // 在类上找注解
            annotation = AnnotatedElementUtils.findMergedAnnotation(
                    joinPoint.getTarget().getClass(), RequirePermission.class
            );
        }
        // 如果没有找到注解，则不需要权限
        if (annotation == null) {
            return joinPoint.proceed();
        }

        UserContext userContext = UserContextHolder.getUserContext();
        boolean permitted = annotation.logical() == RequirePermission.Logical.AND
                // 全量满足
                ? Arrays.stream(annotation.value()).allMatch(one -> userContext.permissions().contains(one))
                : Arrays.stream(annotation.value()).anyMatch(one -> userContext.permissions().contains(one));

        if (!permitted) {
            throw new ForbiddenException(ResultCode.FORBIDDEN);
        }
        return joinPoint.proceed();
    }
}

