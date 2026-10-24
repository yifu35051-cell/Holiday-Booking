package com.holidaybooking.backend.shared.config;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 基础应用配置与启动校验。非法运行模式或无效业务时限会使启动明确失败。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BookingProperties.class)
public class ApplicationConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TransactionTemplate transactions(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    ApplicationRunner validateModes(BookingProperties properties, Environment environment) {
        return args -> {
            if (!java.util.Set.of("mock", "disabled", "alipay-sandbox")
                            .contains(properties.getPaymentMode())
                    || !java.util.Set.of("log", "disabled", "smtp")
                            .contains(properties.getEmailMode())) {
                throw new IllegalStateException("Unsupported payment/email mode");
            }
            if (properties.getEmailDailyLimit() < 1
                    || ("smtp".equals(properties.getEmailMode())
                            && properties.getEmailFrom().isBlank())) {
                throw new IllegalStateException(
                        "A positive email quota and MAIL_FROM for SMTP are required");
            }
            if ("mock".equals(properties.getPaymentMode())
                    && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException("模拟支付只能在local/test配置下启用");
            }
            if (properties.getHoldTtl().isNegative()
                    || properties.getHoldTtl().isZero()
                    || properties.getQuoteTtl().isNegative()
                    || properties.getQuoteTtl().isZero()
                    || properties.getMaxNights() < 1
                    || properties.getAdvanceDays() < 1
                    || properties.getMaxRooms() < 1) {
                throw new IllegalStateException("预订时限和日期范围配置必须为正数");
            }
        };
    }
}
