package com.suretyseven.kafka;

/** Central place naming every topic this app produces to or consumes from. */
public final class KafkaTopics {
    public static final String APPLICATIONS_SUBMITTED = "applications.submitted";
    public static final String APPLICATIONS_DECISIONED = "applications.decisioned";

    private KafkaTopics() {}
}
