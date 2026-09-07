-- =====================================================================
-- V2 reference data: departments, wards, categories.
--
-- This is configuration, not demo data. It ships in every environment.
-- Demo issues and reports arrive separately, behind the demo profile.
--
-- Standing rule 1: every tunable number lives in the categories table.
-- When an examiner asks "how did you choose 25 m?", the answer is that it
-- is configuration derived from the physical extent of the defect and
-- swept empirically in the evaluation, not a constant compiled into a
-- service. Changing a radius is an UPDATE, not a redeploy.
-- =====================================================================

-- ---------------------------------------------------------------------
-- departments: a shallow tree. Levels 1 and 2 of the escalation ladder
-- walk it; level 3 leaves it entirely for the ward officer (DD-005).
-- ---------------------------------------------------------------------

INSERT INTO departments (code, name, parent_department_id) VALUES
    ('PUBLIC_WORKS', 'Public Works Zone', NULL),
    ('CIVIC_SERVICES', 'Civic Services Zone', NULL);

INSERT INTO departments (code, name, parent_department_id) VALUES
    ('ROADS',      'Roads and Infrastructure',
        (SELECT id FROM departments WHERE code = 'PUBLIC_WORKS')),
    ('WATER',      'Water Supply and Drainage',
        (SELECT id FROM departments WHERE code = 'PUBLIC_WORKS')),
    ('ELECTRICAL', 'Street Lighting and Electrical',
        (SELECT id FROM departments WHERE code = 'PUBLIC_WORKS')),
    ('SANITATION', 'Sanitation and Waste',
        (SELECT id FROM departments WHERE code = 'CIVIC_SERVICES')),
    ('ANIMAL_CONTROL', 'Animal Control',
        (SELECT id FROM departments WHERE code = 'CIVIC_SERVICES'));

-- ---------------------------------------------------------------------
-- wards: approximate polygons over Ludhiana, as a 2x2 grid.
--
-- Real municipal boundary data is not published in a usable form, which is
-- recorded as a limitation in the report. What matters for the algorithm is
-- that the polygons are disjoint, cover the service area, and that
-- ST_Contains resolves a coordinate to exactly one of them. A report
-- outside all four is rejected as outside the service area.
--
-- Grid: longitude 75.78 .. 75.94, latitude 30.84 .. 30.96.
-- ---------------------------------------------------------------------

INSERT INTO wards (ward_number, name, boundary) VALUES
    (1, 'Ward 1 - Civil Lines North', ST_Multi(ST_GeomFromText(
        'POLYGON((75.78 30.90, 75.86 30.90, 75.86 30.96, 75.78 30.96, 75.78 30.90))', 4326))),
    (2, 'Ward 2 - Bharat Nagar', ST_Multi(ST_GeomFromText(
        'POLYGON((75.86 30.90, 75.94 30.90, 75.94 30.96, 75.86 30.96, 75.86 30.90))', 4326))),
    (3, 'Ward 3 - Model Town South', ST_Multi(ST_GeomFromText(
        'POLYGON((75.78 30.84, 75.86 30.84, 75.86 30.90, 75.78 30.90, 75.78 30.84))', 4326))),
    (4, 'Ward 4 - Sarabha Nagar', ST_Multi(ST_GeomFromText(
        'POLYGON((75.86 30.84, 75.94 30.84, 75.94 30.90, 75.86 30.90, 75.86 30.84))', 4326)));

-- ---------------------------------------------------------------------
-- categories
--
-- merge_radius_m tracks the physical spatial extent of the defect: a
-- pothole is a point, a damaged road is linear, a stray animal moves.
--
-- low_conf_action (DD-002): MERGE_FLAG takes the optimistic path because a
-- wrong merge costs one click to split. SPLIT_FLAG is for categories where
-- a concealed duplicate is itself the hazard -- an open manhole hidden
-- behind an incremented counter on an existing ticket is a safety failure,
-- not a data-quality one. Both outcomes reach the review queue, so no
-- evidence is lost either way.
--
-- max_extent_multiplier (DD-001): 2.0 is a starting value, not a derived
-- one. It is swept in the evaluation and the cap will be withdrawn if the
-- data does not support it. Linear defects get a looser cap because their
-- true extent genuinely is larger.
-- ---------------------------------------------------------------------

INSERT INTO categories (code, display_name, merge_radius_m, default_sla_hours,
                        severity_weight, reopen_window_days, max_extent_multiplier,
                        low_conf_action, is_mobile_target, department_id) VALUES

    ('POTHOLE', 'Pothole', 25, 72, 15, 14, 2.00, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'ROADS')),

    ('ROAD_DAMAGE', 'Road Damage', 60, 120, 12, 14, 2.50, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'ROADS')),

    ('SIGNAGE', 'Damaged or Missing Signage', 20, 168, 6, 14, 2.00, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'ROADS')),

    ('STREETLIGHT', 'Streetlight Out', 30, 96, 10, 14, 2.00, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'ELECTRICAL')),

    ('GARBAGE_DUMP', 'Garbage Accumulation', 50, 48, 12, 14, 2.00, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'SANITATION')),

    ('ILLEGAL_DUMPING', 'Illegal Dumping', 50, 48, 14, 14, 2.00, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'SANITATION')),

    ('WATER_LEAK', 'Water Leak', 40, 24, 20, 14, 2.00, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'WATER')),

    ('DRAINAGE_BLOCK', 'Blocked Drain', 40, 24, 18, 14, 2.00, 'MERGE_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'WATER')),

    -- Safety-critical. Six-hour SLA, tightest radius, and SPLIT_FLAG: a
    -- second open manhole must not disappear into an existing ticket.
    ('OPEN_MANHOLE', 'Open or Missing Manhole Cover', 20, 6, 40, 14, 1.50, 'SPLIT_FLAG', FALSE,
        (SELECT id FROM departments WHERE code = 'WATER')),

    -- The target moves, so the radius is widest and the reopen window is
    -- shortest: a stray dog seen at the same corner three weeks later is a
    -- new sighting, not a recurrence of an unfixed problem.
    ('STRAY_ANIMAL', 'Stray Animal', 120, 24, 16, 3, 2.00, 'SPLIT_FLAG', TRUE,
        (SELECT id FROM departments WHERE code = 'ANIMAL_CONTROL'));
