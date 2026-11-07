package com.holidaybooking.backend.mail.domain;

import java.util.Map;

/** Plain-text template renderer. User values are substituted once and never evaluated as code. */
public interface MailTemplate {
    String render(Map<String, String> values);
}
