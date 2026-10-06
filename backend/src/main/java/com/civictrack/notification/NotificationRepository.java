package com.civictrack.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Every read and write is scoped by {@code user_id}, which the controller takes
 * from the token. There is deliberately no method that touches a notification
 * by id alone: "mark notification 41 read" from the wrong account must update
 * nothing rather than someone else's row.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query(value = """
            SELECT * FROM notifications
            WHERE user_id = :userId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Notification> findPage(@Param("userId") UUID userId,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    long countByUserId(UUID userId);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE Notification n SET n.readAt = :now
            WHERE n.userId = :userId AND n.id IN :ids AND n.readAt IS NULL
            """)
    int markRead(@Param("userId") UUID userId, @Param("ids") Collection<Long> ids,
                 @Param("now") Instant now);

    /**
     * The registered people who reported an issue, once each however many
     * times they reported it. Anonymous reports have no account to notify.
     * {@code citizensOnly} narrows it to the people who may vote.
     */
    @Query(value = """
            SELECT DISTINCT r.reporter_id FROM reports r
            JOIN users u ON u.id = r.reporter_id
            WHERE r.issue_id = :issueId
              AND u.active
              AND (NOT :citizensOnly OR u.role = 'CITIZEN')
            """, nativeQuery = true)
    List<UUID> findReporterIds(@Param("issueId") UUID issueId,
                               @Param("citizensOnly") boolean citizensOnly);
}
