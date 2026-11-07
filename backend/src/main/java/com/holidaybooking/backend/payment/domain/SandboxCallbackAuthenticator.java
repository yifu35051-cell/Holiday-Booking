package com.holidaybooking.backend.payment.domain;

public interface SandboxCallbackAuthenticator {
    String sign(String timestamp, String body);

    boolean verify(String timestamp, String body, String signature);
}
