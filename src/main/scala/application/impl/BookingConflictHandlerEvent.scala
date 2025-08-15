package application.impl

import application.BookingConflictHandler
import cats.effect.Async
import cats.implicits._
import model.BookingConflict
import org.typelevel.log4cats.Logger
import persistence.BookingStorage

class BookingConflictHandlerEvent[F[_]: Async: Logger](bookingStorage: BookingStorage[F]) extends BookingConflictHandler[F] {
  override def handleBookingConflicts(bookingConflict: BookingConflict): F[Unit] = {
    Logger[F].warn(s"Booking conflict $bookingConflict is going to be written to db") >>
      bookingStorage.saveBookingConflict(bookingConflict.toRequest).flatTap {
        case Some(id) => Logger[F].info(s"Booking conflict with id $id had been created")
        case None => Logger[F].warn(s"Try to write duplicate conflict $bookingConflict")
      }.void //TODO handle possible exception error
  }

}
