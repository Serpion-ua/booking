package application

import application.impl.BookingHandlerImpl
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import graphql.mutation.CreateBooking
import graphql.mutation.CreateBooking.CreateBookingFailure.CreateBookingAlternativeDates
import graphql.query.GetHomeBookings
import model.Booking
import munit.CatsEffectSuite
import org.mockito.ArgumentMatchersSugar._
import org.mockito.MockitoSugar._
import org.scalatest.Assertions.succeed
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import persistence.BookingStorage
import persistence.BookingStorage.{BookingData, BookingsFilters}

import java.time.{Instant, LocalDate}
import java.util.UUID

final class BookingHandlerImplTest extends CatsEffectSuite {
  implicit val logger: Logger[IO] =
    Slf4jLogger.getLoggerFromName[IO]("BookingHandlerImplTest")
  implicit val rt: IORuntime = IORuntime.global

  private def mkHandler(
      storage: BookingStorage[IO],
      conflicts: BookingConflictProducer[IO]
  ): BookingHandlerImpl[IO] =
    new BookingHandlerImpl[IO](storage, conflicts)

  private def mkBooking(
      id: UUID,
      home: UUID,
      from: LocalDate,
      to: LocalDate,
      email: String = "guest@example.com",
      source: String = "Website",
      createdAt: Instant = Instant.now()
  ): Booking =
    Booking(
      id = id,
      homeId = home,
      fromDate = from,
      toDate = to,
      guestEmail = email,
      source = source,
      createdAt = createdAt
    )

  private def mkCreateInput(
      home: UUID,
      from: LocalDate,
      to: LocalDate,
      email: String = "guest@example.com",
      source: String = "Website"
  ): CreateBooking.Input =
    CreateBooking.Input(homeId = home, fromDate = from, toDate = to, guestEmail = email, source = source)

  // ============ createBooking ============

  test("createBooking returns Success when persistence returns Some(id)") {
    val storage = mock[BookingStorage[IO]]
    val conflicts = mock[BookingConflictProducer[IO]]

    val home = UUID.randomUUID()
    val id = UUID.randomUUID()
    val from = LocalDate.parse("2025-08-10")
    val to = LocalDate.parse("2025-08-12")

    when(storage.createBooking(any[BookingData]))
      .thenReturn(IO.pure(Some(id)))

    val handler = mkHandler(storage, conflicts)
    val input = mkCreateInput(home, from, to)

    handler.createBooking(input).map {
      case CreateBooking.CreateBookingSuccess(got) =>
        assertEquals(got, id)
        verify(conflicts, never).processConflict(any[CreateBooking.Input])
      case other =>
        fail(s"Expected Success, got $other")
    }
  }

  test("createBooking returns BookingConflict with AlternativeDates when persistence returns None; calls conflict handler") {
    val storage = mock[BookingStorage[IO]]
    val conflicts = mock[BookingConflictProducer[IO]]

    val home = UUID.randomUUID()
    val from = LocalDate.parse("2025-08-10")
    val to = LocalDate.parse("2025-08-20")

    // Persistence refuses the booking (conflict)
    when(storage.createBooking(any[BookingData]))
      .thenReturn(IO.pure(None))

    // Existing bookings inside the requested range:
    //   [10..20):
    //   Booked: [12..13), [16..18)
    // Free slots: [10..12) (2 days), [13..16) (3 days), [18..20) (2 days)
    // Longest free slot => [13..16)
    val b1 = mkBooking(UUID.randomUUID(), home, LocalDate.parse("2025-08-12"), LocalDate.parse("2025-08-13"))
    val b2 = mkBooking(UUID.randomUUID(), home, LocalDate.parse("2025-08-16"), LocalDate.parse("2025-08-18"))

    when(storage.getHomeBookings(any[BookingsFilters]))
      .thenReturn(IO.pure(Seq(b1, b2)))

    when(conflicts.processConflict(any[CreateBooking.Input]))
      .thenReturn(IO.unit)

    val handler = mkHandler(storage, conflicts)
    val input = mkCreateInput(home, from, to)

    handler.createBooking(input).map {
      case CreateBooking.CreateBookingFailure
            .CreateBookingConflict(Some(CreateBookingAlternativeDates(altFrom, altTo))) =>
        // Longest free window we expect:
        assertEquals(altFrom, LocalDate.parse("2025-08-13"))
        assertEquals(altTo, LocalDate.parse("2025-08-16"))
        verify(conflicts, times(1)).processConflict(eqTo(input))
        succeed
      case other =>
        fail(s"Expected Failure.BookingConflict(Some(AlternativeDates)), got $other")
    }
  }

  test("createBooking returns BookingConflict with None when no free slot exists in requested range") {
    val storage = mock[BookingStorage[IO]]
    val conflicts = mock[BookingConflictProducer[IO]]

    val home = UUID.randomUUID()
    val a = LocalDate.parse("2025-08-10")
    val b = LocalDate.parse("2025-08-15")
    val c = LocalDate.parse("2025-08-20")

    // Persistence refuses the booking (conflict)
    when(storage.createBooking(any[BookingData]))
      .thenReturn(IO.pure(None))

    // Booked slots are back-to-back: [a,b) and [b,c)
    val booking1 = mkBooking(UUID.randomUUID(), home, a, b)
    val booking2 = mkBooking(UUID.randomUUID(), home, b, c)

    when(storage.getHomeBookings(any[BookingsFilters]))
      .thenReturn(IO.pure(Seq(booking1, booking2)))

    when(conflicts.processConflict(any[CreateBooking.Input]))
      .thenReturn(IO.unit)

    val handler = mkHandler(storage, conflicts)

    val input = mkCreateInput(home, a, b)

    handler.createBooking(input).map {
      case CreateBooking.CreateBookingFailure.CreateBookingConflict(None) =>
        verify(conflicts, times(1)).processConflict(eqTo(input))
        succeed
      case other =>
        fail(s"Expected Failure.BookingConflict(None), got $other")
    }
  }

  test("createBooking returns BookingConflict with last days for the same booking") {
    val storage = mock[BookingStorage[IO]]
    val conflicts = mock[BookingConflictProducer[IO]]

    val home = UUID.randomUUID()
    val a = LocalDate.parse("2025-08-10")
    val b = LocalDate.parse("2025-08-15")

    // Persistence refuses the booking (conflict)
    when(storage.createBooking(any[BookingData]))
      .thenReturn(IO.pure(None))

    val booking1 = mkBooking(UUID.randomUUID(), home, a, b)

    when(storage.getHomeBookings(any[BookingsFilters]))
      .thenReturn(IO.pure(Seq(booking1)))

    when(conflicts.processConflict(any[CreateBooking.Input]))
      .thenReturn(IO.unit)

    val handler = mkHandler(storage, conflicts)

    val input = mkCreateInput(home, a, b)

    handler.createBooking(input).map {
      case CreateBooking.CreateBookingFailure.CreateBookingConflict(Some(CreateBookingAlternativeDates(f, t))) =>
        assert(f == t)
        assert(f == b)
        verify(conflicts, times(1)).processConflict(eqTo(input))
        succeed
      case other =>
        fail(s"Expected Failure.BookingConflict(None), got $other")
    }
  }

  test("createBooking maps thrown/failed errors to IncorrectRequest") {
    val storage = mock[BookingStorage[IO]]
    val conflicts = mock[BookingConflictProducer[IO]]

    val home = UUID.randomUUID()
    val from = LocalDate.parse("2025-08-10")
    val to = LocalDate.parse("2025-08-12")
    val boom = new RuntimeException("bad input")

    when(storage.createBooking(any[BookingData]))
      .thenReturn(IO.raiseError(boom))

    val handler = mkHandler(storage, conflicts)
    val input = mkCreateInput(home, from, to)

    handler.createBooking(input).map {
      case CreateBooking.CreateBookingFailure.CreateBookingIncorrectRequest(msg) =>
        assert(msg.contains("bad input"))
        verify(conflicts, never).processConflict(any)
      case other =>
        fail(s"Expected Failure.IncorrectRequest, got $other")
    }
  }

  // ============ getHomeBookings ============

  test("getHomeBookings returns Success with bookings on happy path") {
    val storage = mock[BookingStorage[IO]]
    val conflicts = mock[BookingConflictProducer[IO]]
    val handler = mkHandler(storage, conflicts)

    val home = UUID.randomUUID()
    val input = GetHomeBookings.Input(homeId = home)

    val bookings = Seq(
      mkBooking(UUID.randomUUID(), home, LocalDate.parse("2025-08-10"), LocalDate.parse("2025-08-12")),
      mkBooking(UUID.randomUUID(), home, LocalDate.parse("2025-08-15"), LocalDate.parse("2025-08-17"))
    )

    when(storage.getHomeBookings(any[BookingsFilters]))
      .thenReturn(IO.pure(bookings))

    handler.getHomeBookings(input).map {
      case GetHomeBookings.GetHomeBookingsSuccess(bs) =>
        assertEquals(bs, bookings)
      case other =>
        fail(s"Expected Success, got $other")
    }
  }

  test("getHomeBookings maps errors to IncorrectRequest") {
    val storage = mock[BookingStorage[IO]]
    val conflicts = mock[BookingConflictProducer[IO]]
    val handler = mkHandler(storage, conflicts)

    val home = UUID.randomUUID()
    val input = GetHomeBookings.Input(homeId = home)
    val boom = new RuntimeException("db down")

    when(storage.getHomeBookings(any[BookingsFilters]))
      .thenReturn(IO.raiseError(boom))

    handler.getHomeBookings(input).map {
      case GetHomeBookings.GetHomeBookingsFailure.GetHomeBookingsIncorrectRequest(msg) =>
        assert(msg.contains("db down"))
      case other =>
        fail(s"Expected Failure.IncorrectRequest, got $other")
    }
  }
}
