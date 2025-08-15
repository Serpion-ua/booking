package application

import model.BookingConflict

trait BookingConflictHandler[F[_]] {
  def handleBookingConflicts(bookingConflict: BookingConflict): F[Unit]
}
