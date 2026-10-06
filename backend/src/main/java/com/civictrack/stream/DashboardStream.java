package com.civictrack.stream;

import com.civictrack.issue.IssueTransitioned;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live half of the public dashboard: server-sent events telling every open
 * dashboard that its overdue figure and breaching list may be stale.
 *
 * <p><b>After commit, never inside the transaction.</b> Both listeners are
 * {@code AFTER_COMMIT}. A broadcast from inside the transaction would send
 * browsers to refetch before the change was visible to their query -- they
 * would read the old value and stop listening for it -- and a transaction that
 * then rolled back would have announced something that never happened. The
 * notification rows of DD-062 are the opposite case and the opposite choice:
 * they are data in the same database and must commit with the change.
 *
 * <p><b>Content-free, and coalesced.</b> An event says "changed", not what
 * changed. The client already knows how to fetch the figures, and the server
 * is the only authority on them; shipping deltas would make every browser a
 * second implementation of the breach predicate. And because a verification
 * sweep can settle fifty issues in one pass, events only mark the stream
 * dirty, and {@link #flush} sends at most one "changed" per second. Fifty
 * events become one refetch per open dashboard, not fifty.
 *
 * <p><b>Heartbeat every 25 seconds.</b> Render's proxy closes a connection it
 * sees no bytes on, and a live demo then dies silently. An SSE comment line
 * is ignored by EventSource and keeps the connection open.
 */
@Component
@Slf4j
public class DashboardStream {

    /** Thirty minutes, then the browser's EventSource reconnects on its own. */
    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        // An immediate first event, so the client knows the stream is live --
        // and so a proxy that buffers until the first byte releases the
        // response now rather than at the first heartbeat.
        send(emitter, SseEmitter.event().name("ready").data("ok").reconnectTime(5_000));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransition(IssueTransitioned event) {
        dirty.set(true);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(DashboardChanged event) {
        dirty.set(true);
    }

    /** Sends one "changed" if anything happened since the last flush. */
    @Scheduled(fixedDelayString = "${civictrack.stream.flush-interval:PT1S}")
    public void flush() {
        if (dirty.getAndSet(false)) {
            broadcast(SseEmitter.event().name("changed").data("dashboard"));
        }
    }

    @Scheduled(fixedDelayString = "${civictrack.stream.heartbeat-interval:PT25S}")
    public void heartbeat() {
        broadcast(SseEmitter.event().comment("heartbeat"));
    }

    int subscriberCount() {
        return emitters.size();
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : emitters) {
            send(emitter, event);
        }
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException gone) {
            // The browser left. Not an error worth more than a debug line:
            // closing a tab is the normal way a subscription ends.
            emitters.remove(emitter);
            log.debug("Dropped a dashboard subscriber: {}", gone.getMessage());
        }
    }
}
