package model

import model.Booking._

import java.time.{Instant, LocalDate}
import java.util.UUID

object Booking {
  type Id = UUID
  type HomeId = UUID
  type Date = LocalDate
  type Email = String
  type Source = String
}

case class Booking(
    id: Id,
    homeId: HomeId,
    fromDate: Date,
    toDate: Date,
    guestEmail: Email,
    source: Source,
    createdAt: Instant
)

case class BookingConflict(
    homeId: HomeId,
    fromDate: Date,
    toDate: Date,
    guestEmail: Email,
    source: Source,
    createdAt: Instant
)
