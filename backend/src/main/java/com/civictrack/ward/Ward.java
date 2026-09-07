package com.civictrack.ward;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * An administrative polygon. Clustering never crosses a ward boundary, because
 * a different ward means a different SLA owner and a different escalation path.
 *
 * <p>The boundary geometry itself is deliberately not mapped. Nothing in the
 * application needs a MultiPolygon in memory -- containment is answered by
 * PostGIS via {@code ST_Contains} -- and mapping it would pull a large geometry
 * into every ward load for no purpose.
 */
@Entity
@Table(name = "wards")
@Getter
@Setter
public class Ward {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "ward_number", nullable = false)
    private int wardNumber;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "officer_user_id")
    private UUID officerUserId;
}
