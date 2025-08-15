package application.impl

import application.{BookingConflictProducer, BookingHandler}
import cats.effect._
import cats.implicits._
import graphql.mutation.CreateBooking
import graphql.mutation.CreateBooking.CreateBookingFailure.CreateBookingAlternativeDates
import graphql.query.GetHomeBookings
import model.Booking
import model.Booking.Date
import org.typelevel.log4cats.Logger
import persistence.BookingStorage

import java.time.temporal.ChronoUnit
import scala.collection.mutable.ListBuffer

object BookingHandlerImpl {
  def make[F[_]: Sync: Logger](
      persistence: BookingStorage[F],
      bookingConflictHandler: BookingConflictProducer[F]
  ): F[BookingHandlerImpl[F]] =
    new BookingHandlerImpl[F](persistence, bookingConflictHandler).pure[F]
}

class BookingHandlerImpl[F[_]: Sync: Logger](persistence: BookingStorage[F], bookingConflictHandler: BookingConflictProducer[F])
    extends BookingHandler[F] {

  override def createBooking(input: CreateBooking.Input): F[CreateBooking.CreateBookingResult] = {
    persistence
      .createBooking(input.toRequest)
      .flatMap {
        case Some(bookingId) =>
          Logger[F].info(s"Created booking with id $bookingId") >>
            (CreateBooking.CreateBookingSuccess(bookingId): CreateBooking.CreateBookingResult).pure[F]
        case None =>
          for {
            _ <- Logger[F].info(s"Detected booking conflict for input $input")
            _ <- bookingConflictHandler.processConflict(input)

            getBookingsRequest = BookingStorage.BookingsFilters(input.homeId, input.fromDate.some, input.toDate.some)
            bookings <- persistence.getHomeBookings(getBookingsRequest)
            freeDates = freeDatesInRange(input.fromDate, input.toDate, bookings)
            maxFreeDate = freeDates.sortBy { case (start, end) => ChronoUnit.DAYS.between(start, end) }.lastOption
            alternativeDates = maxFreeDate.map { case (from, to) => CreateBookingAlternativeDates(from, to) }
          } yield CreateBooking.CreateBookingFailure.CreateBookingConflict(alternativeDates): CreateBooking.CreateBookingResult
      }
      .handleError(error => CreateBooking.CreateBookingFailure.CreateBookingIncorrectRequest(error.toString))
  }

  private def freeDatesInRange(from: Date, to: Date, bookings: Seq[Booking]): Seq[(Date, Date)] = {
    val sorted = bookings
      .filter(b => !b.toDate.isBefore(from) && !b.fromDate.isAfter(to))
      .sortBy(_.fromDate)

    val freeSlots = ListBuffer.empty[(Date, Date)]

    var currentStart = from

    sorted.foreach { b =>
      if (currentStart.isBefore(b.fromDate)) {
        freeSlots += ((currentStart, b.fromDate))
      }
      if (b.toDate.isAfter(currentStart)) {
        currentStart = b.toDate
      }
    }

    // If there's space after the last booking
    if (currentStart.isBefore(to) || currentStart.isEqual(to)) {
      freeSlots += ((currentStart, to))
    }

    freeSlots.toSeq
  }

  override def getHomeBookings(input: GetHomeBookings.Input): F[GetHomeBookings.GetHomeBookingsResult] =
    persistence
      .getHomeBookings(input.toRequest)
      .map(bookings => GetHomeBookings.GetHomeBookingsSuccess(bookings): GetHomeBookings.GetHomeBookingsResult)
      .handleError(error => GetHomeBookings.GetHomeBookingsFailure.GetHomeBookingsIncorrectRequest(error.toString))
}
