package com.suretyseven.domain;

public enum OutboxStatus {
    PENDING,
    SENT,
    DEAD_LETTER
}
