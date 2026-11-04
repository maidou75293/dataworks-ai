package com.lab.dataworks.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点「任一满足」即可（v460）。
 *
 * <p>{@link RequireAtom} 是「全部满足」语义，适合一个动作需要多个点同时具备的场景；
 * 但权限申请页/审批页这类入口，申请人、审批员、回收员是不同的角色集合，
 * 用「任一」才不会把「只批不申请」的角色挡在门外。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAnyAtom {
    String[] value();
}
