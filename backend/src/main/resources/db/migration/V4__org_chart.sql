-- =====================================================================
-- V4: the org chart the escalation ladder resolves against.
--
-- DD-005 makes escalation an explicit ordered resolver: level 1 is the
-- department head, level 2 the parent department's head, level 3 the ward
-- officer, level 4 the administrator. Every one of those is a foreign key
-- into users, and on a database seeded only with V2 all four are NULL --
-- so an issue that breached would climb the ladder into nobody. This
-- migration is what makes the ladder resolvable on a fresh install.
--
-- These accounts are seeded WITHOUT a password hash, deliberately.
--
-- A migration is the wrong place to ship credentials: it is committed to
-- version control, it is identical in every environment, and it is applied
-- to production automatically. What ships here is an org chart -- who is
-- accountable for what -- not a set of logins. AuthService refuses any
-- account whose password_hash is null before it reaches the encoder, so
-- these rows can own work and receive escalations but cannot be logged
-- into until somebody sets a password deliberately. The demo profile does
-- exactly that at startup, from configuration, in DemoAccountBootstrap.
--
-- Roles: department heads and ward officers are SUPERVISOR. A ward officer
-- has a ward and no department, which is what lets them act on any issue in
-- their ward -- necessary, because level 3 hands them issues across
-- departmental lines.
-- =====================================================================

INSERT INTO users (email, full_name, role, department_id, ward_id) VALUES
    ('commissioner@civictrack.example', 'Municipal Commissioner', 'ADMIN', NULL, NULL);

-- ---------------------------------------------------------------------
-- zone heads (level 2 of the ladder, reached via parent_department_id)
-- ---------------------------------------------------------------------

INSERT INTO users (email, full_name, role, department_id) VALUES
    ('head.public-works@civictrack.example', 'Head, Public Works Zone', 'SUPERVISOR',
        (SELECT id FROM departments WHERE code = 'PUBLIC_WORKS')),
    ('head.civic-services@civictrack.example', 'Head, Civic Services Zone', 'SUPERVISOR',
        (SELECT id FROM departments WHERE code = 'CIVIC_SERVICES'));

-- ---------------------------------------------------------------------
-- departmental heads (level 1)
-- ---------------------------------------------------------------------

INSERT INTO users (email, full_name, role, department_id) VALUES
    ('head.roads@civictrack.example', 'Head, Roads and Infrastructure', 'SUPERVISOR',
        (SELECT id FROM departments WHERE code = 'ROADS')),
    ('head.water@civictrack.example', 'Head, Water Supply and Drainage', 'SUPERVISOR',
        (SELECT id FROM departments WHERE code = 'WATER')),
    ('head.electrical@civictrack.example', 'Head, Street Lighting and Electrical', 'SUPERVISOR',
        (SELECT id FROM departments WHERE code = 'ELECTRICAL')),
    ('head.sanitation@civictrack.example', 'Head, Sanitation and Waste', 'SUPERVISOR',
        (SELECT id FROM departments WHERE code = 'SANITATION')),
    ('head.animal-control@civictrack.example', 'Head, Animal Control', 'SUPERVISOR',
        (SELECT id FROM departments WHERE code = 'ANIMAL_CONTROL'));

-- ---------------------------------------------------------------------
-- ward officers (level 3) -- accountable for a place, not for a service
-- ---------------------------------------------------------------------

INSERT INTO users (email, full_name, role, department_id, ward_id) VALUES
    ('officer.ward1@civictrack.example', 'Ward 1 Officer', 'SUPERVISOR', NULL,
        (SELECT id FROM wards WHERE ward_number = 1)),
    ('officer.ward2@civictrack.example', 'Ward 2 Officer', 'SUPERVISOR', NULL,
        (SELECT id FROM wards WHERE ward_number = 2)),
    ('officer.ward3@civictrack.example', 'Ward 3 Officer', 'SUPERVISOR', NULL,
        (SELECT id FROM wards WHERE ward_number = 3)),
    ('officer.ward4@civictrack.example', 'Ward 4 Officer', 'SUPERVISOR', NULL,
        (SELECT id FROM wards WHERE ward_number = 4));

-- ---------------------------------------------------------------------
-- crew (level 0 -- the assignee, where escalation starts from)
-- ---------------------------------------------------------------------

INSERT INTO users (email, full_name, role, department_id) VALUES
    ('crew.roads@civictrack.example', 'Roads Crew Member', 'STAFF',
        (SELECT id FROM departments WHERE code = 'ROADS')),
    ('crew.water@civictrack.example', 'Water Crew Member', 'STAFF',
        (SELECT id FROM departments WHERE code = 'WATER')),
    ('crew.sanitation@civictrack.example', 'Sanitation Crew Member', 'STAFF',
        (SELECT id FROM departments WHERE code = 'SANITATION'));

-- ---------------------------------------------------------------------
-- wire the ladder
-- ---------------------------------------------------------------------

UPDATE departments d SET head_user_id = u.id
FROM users u
WHERE u.department_id = d.id
  AND u.role = 'SUPERVISOR'
  AND u.email = 'head.' || lower(replace(d.code, '_', '-')) || '@civictrack.example';

UPDATE wards w SET officer_user_id = u.id
FROM users u
WHERE u.ward_id = w.id AND u.role = 'SUPERVISOR' AND u.department_id IS NULL;
