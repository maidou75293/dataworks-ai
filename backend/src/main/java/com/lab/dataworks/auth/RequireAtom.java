package com.lab.dataworks.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 按权限点判定（权限模型第二期）。优先于旧的 {@link RequirePermission}：
 * 两者同时存在时只看本注解。
 *
 * <p>语义：调用方必须**同时具备** value 里的全部权限点（all-of）。需要「任一」时
 * 单独拆接口或改代码，不在注解里表达，避免出现两种判定语义混用。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAtom {
    String[] value();
}
