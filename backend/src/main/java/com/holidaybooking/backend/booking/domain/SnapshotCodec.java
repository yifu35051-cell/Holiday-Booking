package com.holidaybooking.backend.booking.domain;

/** 报价快照编解码端口，将业务模型与具体 JSON 实现分离。 */
public interface SnapshotCodec {
    String encode(QuoteSnapshot snapshot);

    QuoteSnapshot decode(String data);
}
