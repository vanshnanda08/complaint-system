package com.civictrack.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findByIssueIdOrderByCreatedAtAsc(UUID issueId);

    int countByIssueId(UUID issueId);
}
