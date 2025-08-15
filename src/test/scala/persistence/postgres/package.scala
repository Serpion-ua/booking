package persistence

import persistence.BookingStorage.BookingConflictData

import java.time.Instant
import java.time.temporal.ChronoUnit

package object postgres {
  implicit class BookingConflictDataOps(val b: BookingConflictData) extends AnyVal {
    def ===(other: BookingConflictData): Boolean = {
      b.homeId == other.homeId &&
        b.fromDate == other.fromDate &&
        b.toDate == other.toDate &&
        b.guestEmail == other.guestEmail &&
        b.source == other.source &&
        microsEqual(b.createdAt, other.createdAt)
    }

    private def microsEqual(a: Instant, b: Instant): Boolean = {
      val diffMicros = Math.abs(ChronoUnit.MICROS.between(a, b))
      diffMicros <= 1
    }
  }
}
