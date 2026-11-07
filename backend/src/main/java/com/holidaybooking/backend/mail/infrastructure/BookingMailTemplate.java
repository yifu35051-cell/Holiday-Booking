package com.holidaybooking.backend.mail.infrastructure;

import com.holidaybooking.backend.mail.domain.MailTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class BookingMailTemplate implements MailTemplate {
    private final String content;

    public BookingMailTemplate() throws IOException {
        content =
                new ClassPathResource("mail/booking.txt")
                        .getContentAsString(StandardCharsets.UTF_8);
    }

    public String render(Map<String, String> values) {
        return Pattern.compile("\\{\\{([A-Za-z]+)}}")
                .matcher(content)
                .replaceAll(
                        match ->
                                java.util.regex.Matcher.quoteReplacement(
                                        values.getOrDefault(match.group(1), "")));
    }
}
