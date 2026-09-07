package com.civictrack.sla.escalation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EscalationEventRepository extends JpaRepository<EscalationEvent, Long> {

    List<EscalationEvent> findByIssueIdOrderByLevelAsc(UUID issueId);

    int countByIssueId(UUID issueId);

    boolean existsByIssueIdAndLevel(UUID issueId, int level);
}
