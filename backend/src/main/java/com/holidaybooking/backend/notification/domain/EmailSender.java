package com.holidaybooking.backend.notification.domain;

/** 邮件传输端口。区分已接受、模拟、可重试、未知和失败，避免将网络超时误判为未发送。 */
public interface EmailSender {
    enum Outcome {
        SENT,
        SIMULATED,
        RETRY,
        UNKNOWN,
        FAILED
    }

    record Result(Outcome outcome, String detail) {}

    Result send(NotificationRepository.Task task);
}
