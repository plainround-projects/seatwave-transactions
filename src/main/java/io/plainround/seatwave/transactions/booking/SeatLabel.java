package io.plainround.seatwave.transactions.booking;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One seat's label inside an event, such as {@code A-7}: a section code, a dash and a seat number.
 * The form matches the schema's {@code seat_label_matches} check, so a label that passes here can
 * be stored. Immutable and thread-safe.
 *
 * @param text the label
 */
public record SeatLabel(String text) implements Comparable<SeatLabel> {

  // One to four capital letters (V1's section_code_format), a dash, a number from 1 to 9999.
  private static final Pattern FORM = Pattern.compile("[A-Z]{1,4}-[1-9][0-9]{0,3}");

  /**
   * Checks the form.
   *
   * @throws IllegalArgumentException if the text is not a seat label; the message shows the text
   */
  public SeatLabel {
    Objects.requireNonNull(text, "text");
    if (!FORM.matcher(text).matches()) {
      throw new IllegalArgumentException(
          "a seat label is a section and a number such as A-7, but was '" + text + "'");
    }
  }

  /**
   * Orders labels the way every transaction locks seats: by the label's text. Any fixed order
   * prevents deadlock; this one needs no lookup.
   */
  @Override
  public int compareTo(SeatLabel other) {
    return text.compareTo(other.text);
  }
}
