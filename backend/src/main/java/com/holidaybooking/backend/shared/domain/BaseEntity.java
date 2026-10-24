package com.holidaybooking.backend.shared.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

/** Shared persistence metadata, never accepted directly from HTTP requests. */
@Getter
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void updated() {
        updatedAt = Instant.now();
    }
}
