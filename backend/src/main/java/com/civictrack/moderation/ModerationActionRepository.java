package com.civictrack.moderation;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** The moderation log. Save and read; nothing here can change or remove an entry. */
public interface ModerationActionRepository extends Repository<ModerationAction, Long> {

    ModerationAction save(ModerationAction action);

    /** Everything that touched an issue, as the subject or as the other side of a split or merge. */
    @Query("""
            SELECT a FROM ModerationAction a
            WHERE a.issueId = :issueId OR a.relatedIssueId = :issueId
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<ModerationAction> findTouching(@Param("issueId") UUID issueId);
}
