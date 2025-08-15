package application

import graphql.mutation.CreateBooking
import graphql.query.GetHomeBookings
import model.BookingConflict
import persistence.BookingStorage

import java.time.Instant

package object impl {

  implicit class CreateBookingInputOps(input: CreateBooking.Input) {
    def toRequest: BookingStorage.BookingData =
      BookingStorage.BookingData(input.homeId, input.fromDate, input.toDate, input.guestEmail, input.source)

    def toConflict: BookingConflict =
      BookingConflict(input.homeId, input.fromDate, input.toDate, input.guestEmail, input.source, Instant.now())
  }

  implicit class GetHomeBookingsInputOps(input: GetHomeBookings.Input) {
    //probably we could be interested only in future bookings, only so toDate shall be after now then
    def toRequest: BookingStorage.BookingsFilters =
      BookingStorage.BookingsFilters(input.homeId, None, None)
  }

  implicit class BookingConflictOps(conflict: BookingConflict) {
    def toRequest: BookingStorage.BookingConflictData =
      BookingStorage.BookingConflictData(
        conflict.homeId,
        conflict.fromDate,
        conflict.toDate,
        conflict.guestEmail,
        conflict.source,
        conflict.createdAt
      )
  }

}
