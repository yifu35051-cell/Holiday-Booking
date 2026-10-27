package com.holidaybooking.backend.support;

import java.lang.annotation.*;

/** 输入与预期契约；实际结果由运行监听器另行采集。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CaseSpec {
    String input();

    String expected();
}
