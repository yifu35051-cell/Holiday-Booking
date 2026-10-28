package com.holidaybooking.backend.mail.domain;

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

    /** 发件身份由应用从登录账户生成；SMTP From仍使用已配置的服务发信地址。 */
    record Message(String replyTo, String recipient, String subject, String body) {}

    Result sendMessage(Message message);
}
