package io.plainround.seatwave.transactions.booking;

/** Why a confirm was refused; a refused confirm sells nothing. */
public sealed interface ConfirmRefusal {

  /**
   * The hold is unknown, belongs to another customer, has expired, or has already ended (released
   * or confirmed). Module 7 answers 409 for all four, because the client's next step is the same.
   */
  record HoldNotActive() implements ConfirmRefusal {}
}
