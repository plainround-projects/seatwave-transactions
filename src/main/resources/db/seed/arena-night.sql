-- Demo data for the labs, not a migration: Module 1's arena on-sale. One venue, event 1 with
-- 20,000 seats in 20 sections (A to T) of 1,000, every section at $80.00 (Module 2's illustrative
-- average ticket). The start time is the one Module 1 printed and Module 10 stored.
INSERT INTO venues (venue_id, name) VALUES (1, 'Arena');

INSERT INTO events (event_id, venue_id, name, starts_at)
VALUES (1, 1, 'Arena Night', TIMESTAMPTZ '2026-12-08 20:20:51.783+00');

INSERT INTO sections (event_id, section_code, price, currency)
SELECT 1, chr(64 + section_index), 80.00, 'USD' FROM generate_series(1, 20) AS sections(section_index);

INSERT INTO seats (event_id, seat_label, section_code, seat_number)
SELECT 1, chr(65 + (seat_index - 1) / 1000) || '-' || (((seat_index - 1) % 1000) + 1),
       chr(65 + (seat_index - 1) / 1000), ((seat_index - 1) % 1000) + 1
FROM generate_series(1, 20000) AS seats(seat_index);
