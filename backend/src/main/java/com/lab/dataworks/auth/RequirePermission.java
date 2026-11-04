package com.lab.dataworks.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注接口所需权限。方法上的注解优先于类上的注解。
 *
 * <p>没有标注时：GET 只要求 READ；其它方法一律拒绝（默认失败关闭），
 * 避免新增写接口忘记加保护。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    Permission value();
}
