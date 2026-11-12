package io.plainround.seatwave.transactions.booking;

import java.util.Objects;

/**
 * Why a hold was refused: the expected failures of a hold, each a value, never an exception. A
 * refused request holds nothing, because its transaction was rolled back.
 */
public sealed interface HoldRefusal {

  /**
   * Another customer holds or bought this seat (Module 7 answers 409).
   *
   * @param seat the first seat of the request that was taken
   */
  record SeatTaken(SeatLabel seat) implements HoldRefusal {
    /** Checks that the seat is named. */
    public SeatTaken {
      Objects.requireNonNull(seat, "seat");
    }
  }

  /** The customer already has four active holds (the trigger {@code hold_limit_per_customer}). */
  record LimitReached() implements HoldRefusal {}

  /**
   * The event has no such seat (the foreign key {@code hold_names_a_real_seat}).
   *
   * @param seat the seat that does not exist
   */
  record UnknownSeat(SeatLabel seat) implements HoldRefusal {
    /** Checks that the seat is named. */
    public UnknownSeat {
      Objects.requireNonNull(seat, "seat");
    }
  }
}
