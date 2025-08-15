package persistence

import model.Booking
import model.Booking.{Date, Email, HomeId, Source}
import persistence.BookingStorage.{BookingConflictData, BookingData, BookingsFilters}

import java.security.MessageDigest
import java.time.Instant

trait BookingStorage[F[_]] {

  /** Implementation shall resolve any possible conflicts / races for booking
    * @param request new possible booking information
    * @return Optional Id of created booking; If booking is not possible due conflicts then None
    */
  def createBooking(request: BookingData): F[Option[Booking.Id]]

  /** Get all available bookings for home
    * @param request request with homeId
    * @return all available bookings
    */
  def getHomeBookings(request: BookingsFilters): F[Seq[Booking]]

  /** Save information about conflict
    * @param request booking conflict to save
    * @return id of conflict in database
    */
  def saveBookingConflict(request: BookingConflictData): F[Option[Long]]
}

object BookingStorage {
  case class BookingData(homeId: HomeId, fromDate: Date, toDate: Date, guestEmail: Email, source: Source)

  case class BookingsFilters(homeId: HomeId, fromDate: Option[Date], toDate: Option[Date])

  case class BookingConflictData(
      homeId: HomeId,
      fromDate: Date,
      toDate: Date,
      guestEmail: Email,
      source: Source,
      createdAt: Instant
  ) {
    def idempotencyKey: String = {
      val input = s"$homeId|$fromDate|$toDate|$guestEmail|$source|$createdAt"
      MessageDigest
        .getInstance("SHA-256")
        .digest(input.getBytes("UTF-8"))
        .map("%02x".format(_))
        .mkString
    }
  }
}
