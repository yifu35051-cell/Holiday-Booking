package com.holidaybooking.backend.shared;

import static com.holidaybooking.backend.support.TestEvidence.*;

import com.holidaybooking.backend.support.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@Tag("core")
class BackendApplicationTests extends MySqlTestSupport {
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    @CaseSpec(input = "启动test profile，执行Flyway并装配Spring容器", expected = "数据源和预订服务Bean存在，应用上下文成功启动")
    void contextLoads() {
        org.assertj.core.api.Assertions.assertThat(actual(context.containsBean("bookingService")))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(actual(context.containsBean("dataSource")))
                .isTrue();
    }
}
