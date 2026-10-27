package com.holidaybooking.backend.support;

import org.springframework.test.context.ContextConfiguration;

/** 持久化测试共用配置入口；每个 Spring 上下文使用独立临时 MySQL。 */
@ContextConfiguration(initializers = MySqlTestInitializer.class)
public abstract class MySqlTestSupport {}
