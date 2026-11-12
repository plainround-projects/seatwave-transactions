-- Lab 1's solution: the smallest schema that cannot sell one seat twice, Module 1's shape on
-- PostgreSQL. The seat row carries its own state, so one conditional UPDATE decides a hold.
CREATE TABLE lab1_seats (
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
  hold_customer VARCHAR(64),
  hold_expires_at TIMESTAMPTZ,
  CONSTRAINT lab1_seats_pkey PRIMARY KEY (event_id, seat_label),
  CONSTRAINT lab1_known_status CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
  -- A held or sold seat names its customer and its time; an available one names neither.
  CONSTRAINT lab1_hold_columns_match_status
    CHECK ((status = 'AVAILABLE') = (hold_customer IS NULL AND hold_expires_at IS NULL))
);

CREATE TABLE lab1_bookings (
  booking_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer VARCHAR(64) NOT NULL,
  confirmed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT lab1_bookings_pkey PRIMARY KEY (booking_id),
  CONSTRAINT lab1_booking_of_a_real_seat FOREIGN KEY (event_id, seat_label)
    REFERENCES lab1_seats (event_id, seat_label),
  -- The second guard: even a confirm with a bug cannot write two bookings for one seat.
  CONSTRAINT lab1_one_booking_per_seat UNIQUE (event_id, seat_label)
);

INSERT INTO lab1_seats (event_id, seat_label) VALUES (1, 'A-7');

-- SeatWave's role may change a seat's state and add bookings; it may not delete either.
GRANT SELECT, UPDATE ON lab1_seats TO seatwave_app;
GRANT SELECT, INSERT ON lab1_bookings TO seatwave_app;
