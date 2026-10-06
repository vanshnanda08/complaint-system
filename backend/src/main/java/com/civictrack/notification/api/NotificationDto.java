package com.civictrack.notification.api;

import com.civictrack.notification.Notification;
import com.civictrack.notification.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationDto(long id, UUID issueId, NotificationType type, String title,
                              String body, Instant createdAt, Instant readAt) {

    static NotificationDto from(Notification n) {
        return new NotificationDto(n.getId(), n.getIssueId(), n.getType(), n.getTitle(),
                n.getBody(), n.getCreatedAt(), n.getReadAt());
    }
}
