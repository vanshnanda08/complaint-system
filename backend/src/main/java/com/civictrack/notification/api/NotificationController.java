package com.civictrack.notification.api;

import com.civictrack.notification.NotificationRepository;
import com.civictrack.user.Actor;
import com.civictrack.user.auth.Actors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * The caller's notifications. As with {@code /me/reports}, the user comes
 * from the token and there is no parameter that could name anybody else.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final int MAX_PAGE = 100;

    private final NotificationRepository notifications;
    private final Clock clock;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public NotificationPage list(@RequestParam(defaultValue = "50") int limit,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @AuthenticationPrincipal Jwt jwt) {
        Actor actor = Actors.from(jwt);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        int boundedOffset = Math.max(offset, 0);
        return new NotificationPage(
                notifications.findPage(actor.id(), boundedLimit, boundedOffset).stream()
                        .map(NotificationDto::from).toList(),
                notifications.countByUserId(actor.id()),
                notifications.countByUserIdAndReadAtIsNull(actor.id()),
                boundedLimit, boundedOffset);
    }

    /** Cheap enough to poll: one index-only count on idx_notif_user. */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public UnreadCount unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCount(notifications.countByUserIdAndReadAtIsNull(Actors.from(jwt).id()));
    }

    /**
     * Marks the listed notifications read, or all of them when no ids are
     * given -- the mark-all-read control of blueprint 3.13 is the empty body.
     * Ids belonging to somebody else are ignored, not refused, so the response
     * does not confirm which ids exist.
     */
    @PostMapping("/read")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public UnreadCount markRead(@RequestBody(required = false) MarkReadRequest body,
                                @AuthenticationPrincipal Jwt jwt) {
        Actor actor = Actors.from(jwt);
        if (body == null || body.ids() == null || body.ids().isEmpty()) {
            notifications.markAllRead(actor.id(), clock.instant());
        } else {
            notifications.markRead(actor.id(), body.ids(), clock.instant());
        }
        return new UnreadCount(notifications.countByUserIdAndReadAtIsNull(actor.id()));
    }

    public record MarkReadRequest(List<Long> ids) {
    }

    public record UnreadCount(long unread) {
    }

    public record NotificationPage(List<NotificationDto> items, long total, long unread,
                                   int limit, int offset) {
    }
}
