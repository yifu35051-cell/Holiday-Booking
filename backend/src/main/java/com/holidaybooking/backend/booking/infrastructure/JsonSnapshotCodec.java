package com.holidaybooking.backend.booking.infrastructure;

import com.holidaybooking.backend.booking.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 使用 Spring 管理的 Jackson 将可信报价快照保存为 JSON 文本。 */
@Component
@RequiredArgsConstructor
public class JsonSnapshotCodec implements SnapshotCodec {
    private final ObjectMapper mapper;

    public String encode(QuoteSnapshot snapshot) {
        return mapper.writeValueAsString(snapshot);
    }

    public QuoteSnapshot decode(String data) {
        return mapper.readValue(data, QuoteSnapshot.class);
    }
}
