package com.holidaybooking.backend.integration;

import com.holidaybooking.backend.booking.BookingServiceTest;

/** 独立预订事务回归入口；与其余持久化测试一样使用隔离 MySQL，Docker 不可用即失败。 */
class MySqlBookingIT extends BookingServiceTest {}
