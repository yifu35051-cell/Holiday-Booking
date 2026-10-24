package com.holidaybooking.backend.booking.domain;

import com.holidaybooking.backend.shared.domain.DomainException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Checkout is exclusive: a departing guest and an arriving guest may use the same room. */
public record Stay(LocalDate checkIn, LocalDate checkOut) {
    public Stay {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw DomainException.invalid("INVALID_STAY", "离店日期必须晚于入住日期");
        }
    }

    public int nights() {
        return Math.toIntExact(ChronoUnit.DAYS.between(checkIn, checkOut));
    }

    public List<LocalDate> dates() {
        return checkIn.datesUntil(checkOut).toList();
    }

    public void validate(
            Clock clock, ZoneId zone, LocalTime cutoff, int maxNights, int advanceDays) {
        var now = clock.instant().atZone(zone);
        if (checkIn.isBefore(now.toLocalDate())
                || checkIn.isAfter(now.toLocalDate().plusDays(advanceDays))
                || nights() > maxNights
                || (checkIn.equals(now.toLocalDate()) && !now.toLocalTime().isBefore(cutoff))) {
            throw DomainException.invalid("STAY_OUT_OF_RANGE", "入住日期超出可预订范围");
        }
    }
}
