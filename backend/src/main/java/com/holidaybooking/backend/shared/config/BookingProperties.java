package com.holidaybooking.backend.shared.config;

import java.time.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational settings; credentials are supplied through environment variables. */
@Getter
@Setter
@ConfigurationProperties("booking")
public class BookingProperties {
    private ZoneId zone = ZoneId.of("Asia/Shanghai");
    private Duration quoteTtl = Duration.ofMinutes(10);
    private Duration holdTtl = Duration.ofMinutes(15);
    private int advanceDays = 365;
    private int maxNights = 30;
    private int maxRooms = 10;
    private LocalTime checkInTime = LocalTime.of(15, 0);
    private LocalTime reminderTime = LocalTime.of(9, 0);
    private boolean jobsEnabled = true;
    private String bootstrapEmail = "";
    private String bootstrapPassword = "";
    private String paymentMode = "disabled";
    private String emailMode = "disabled";
    private int emailDailyLimit = 50;
    private String emailFrom = "";
    private List<String> emailAllowlist = List.of();
}
