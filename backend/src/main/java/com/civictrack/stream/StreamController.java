package com.civictrack.stream;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/stream/dashboard}. Public, like the dashboard it feeds;
 * {@code /api/v1/stream/**} has been permitAll in SecurityConfig since phase 1
 * for exactly this.
 *
 * <p>Holds no database connection while open: the emitter is an async
 * response, and with {@code open-in-view=false} nothing ties a pooled
 * connection to it. With Hikari capped at 8, a connection per watching
 * browser would be the whole pool.
 */
@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class StreamController {

    private final DashboardStream stream;

    @GetMapping(path = "/dashboard", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dashboard(HttpServletResponse response) {
        // nginx-style proxies (Render's included) buffer responses by default,
        // which holds SSE events back until the buffer fills.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return stream.subscribe();
    }
}
