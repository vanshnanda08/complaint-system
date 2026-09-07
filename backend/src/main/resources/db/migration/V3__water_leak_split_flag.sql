-- =====================================================================
-- V3: WATER_LEAK takes SPLIT_FLAG in the low-confidence band.
--
-- This is a NEW migration rather than an edit to V2__reference_data.sql,
-- and that is not a stylistic preference. V2 has already been applied to
-- every developer database and to CI. Flyway records a checksum per applied
-- migration and validates it on every subsequent start, so editing V2 in
-- place would make the application refuse to boot against any database that
-- already ran it -- reported as a checksum mismatch, which reads like
-- corruption rather than like an intentional change. An applied migration is
-- immutable; corrections are always forward.
--
-- Why the value changes (DD-002):
--
-- Report section 12.2 states the SPLIT_FLAG rule as covering safety-critical
-- and property-damage categories, but the V2 seed applied it only to
-- OPEN_MANHOLE. WATER_LEAK is the clearest property-damage category in the
-- set: severity 20, the highest after OPEN_MANHOLE, on a 24-hour SLA. A
-- second leak folded into an existing ticket goes unrepaired while the first
-- is fixed and the ticket closes -- the same failure mode that justifies the
-- manhole case, differing in consequence rather than in kind.
--
-- DRAINAGE_BLOCK is deliberately NOT included. It backs up along a line
-- rather than at a point, which is what its 40 m radius already encodes, so
-- splitting near the band boundary would fragment what is usually one
-- blockage into several tickets and load the review queue with rejoins.
-- =====================================================================

UPDATE categories
SET low_conf_action = 'SPLIT_FLAG'
WHERE code = 'WATER_LEAK';
