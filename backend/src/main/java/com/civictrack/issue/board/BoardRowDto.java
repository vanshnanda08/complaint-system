package com.civictrack.issue.board;

import com.civictrack.issue.dto.QueueRowDto;

import java.util.List;
import java.util.UUID;

/**
 * One row of the assignment board (blueprint 3.16): the staff queue row, plus
 * who reported it and who holds it, by name.
 *
 * <p>"Reporter identities visible here and nowhere below this role." That
 * sentence is why this is its own record in a supervisor-only endpoint rather
 * than two fields added to {@link QueueRowDto}: the staff queue serves crew,
 * and a field added there would reach every crew member's screen. Anonymous
 * reporters have no identity to show, so they are a count.
 */
public record BoardRowDto(
        QueueRowDto row,
        List<Reporter> reporters,
        int anonymousReporters,
        String assigneeName
) {
    public record Reporter(UUID id, String fullName) {
    }
}
