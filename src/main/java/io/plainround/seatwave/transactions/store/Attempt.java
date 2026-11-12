package io.plainround.seatwave.transactions.store;

/** How one racing hold attempt ended, as the concurrency tests and the report count it. */
public enum Attempt {
  /** The attempt committed a hold. */
  GRANTED,
  /** The code saw the seat as taken and wrote nothing. */
  SEAT_TAKEN,
  /** The code saw the customer at the limit and wrote nothing. */
  LIMIT_REACHED,
  /** The code tried to write, and a constraint or the trigger refused the row: the last guard. */
  REFUSED_BY_DATABASE
}
