package com.civictrack.stream;

import com.civictrack.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * The stream's two promises: an event reaches browsers only after the change
 * it announces has committed, and never for a change that rolled back.
 *
 * <p>The scheduled flush is slowed to an hour here so that every send in these
 * tests is one the test made, by calling {@link DashboardStream#flush}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "civictrack.stream.flush-interval=PT1H",
        "civictrack.stream.heartbeat-interval=PT1H"})
class DashboardStreamIT extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private DashboardStream stream;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("a subscriber is told the stream is live, without signing in")
    void subscribe() throws Exception {
        MvcResult r = open();
        assertThat(r.getResponse().getContentAsString()).contains("event:ready");
        assertThat(r.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    @DisplayName("nothing is sent while the transaction is open; one event once it commits")
    void afterCommitOnly() throws Exception {
        MvcResult r = open();
        stream.flush(); // drain anything another test left dirty

        tx.executeWithoutResult(status -> {
            events.publishEvent(new DashboardChanged("test"));
            stream.flush();
        });
        String duringTx = r.getResponse().getContentAsString();
        assertThat(duringTx).doesNotContain("event:changed");

        stream.flush();
        assertThat(r.getResponse().getContentAsString()).contains("event:changed");
    }

    @Test
    @DisplayName("a rolled-back change is never announced")
    void rollbackIsSilent() throws Exception {
        MvcResult r = open();
        stream.flush();

        tx.executeWithoutResult(status -> {
            events.publishEvent(new DashboardChanged("test"));
            status.setRollbackOnly();
        });
        stream.flush();

        assertThat(r.getResponse().getContentAsString()).doesNotContain("event:changed");
    }

    @Test
    @DisplayName("fifty changes in a burst reach a browser as one event")
    void coalesced() throws Exception {
        MvcResult r = open();
        stream.flush();

        for (int i = 0; i < 50; i++) {
            tx.executeWithoutResult(status -> events.publishEvent(new DashboardChanged("burst")));
        }
        stream.flush();
        stream.flush();

        String body = r.getResponse().getContentAsString();
        assertThat(body.split("event:changed", -1)).hasSize(2);
    }

    @Test
    @DisplayName("the heartbeat is an SSE comment, which EventSource ignores")
    void heartbeat() throws Exception {
        MvcResult r = open();
        stream.heartbeat();
        assertThat(r.getResponse().getContentAsString()).contains(":heartbeat");
    }

    private MvcResult open() throws Exception {
        return mvc.perform(get("/api/v1/stream/dashboard"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }
}
