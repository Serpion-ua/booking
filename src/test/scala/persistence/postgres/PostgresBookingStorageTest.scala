package persistence.postgres

import application.{ApplicationConfig, DbConfig}
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, IO, Resource}
import cats.implicits._
import doobie.Transactor
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.fragment.Fragment
import munit.CatsEffectSuite
import org.postgresql.util.PSQLException
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import persistence.BookingStorage.{BookingConflictData, BookingData, BookingsFilters}
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.util.Random

class PostgresBookingStorageTest extends CatsEffectSuite {
  implicit val logger: Logger[IO] =
    Slf4jLogger.getLoggerFromName[IO]("PostgresBookingStorageTest")

  val config: ApplicationConfig = ConfigSource.default.loadOrThrow[ApplicationConfig]
  val bookingsTestTableName = s"${config.db.bookingTable}_itest_${Random.nextInt(Int.MaxValue).toString}"
  val bookingConflictsTestTableName = s"${config.db.bookingConflictTable}_itest_${Random.nextInt(Int.MaxValue).toString}"
  val dbConfig: DbConfig =
    config.db.copy(bookingTable = bookingsTestTableName, bookingConflictTable = bookingConflictsTestTableName)

  private val bookingRequestFrom = LocalDate.parse("2025-08-10")
  private val bookingRequestTo = LocalDate.parse("2025-08-12")

  private val bookingRequest =
    BookingData(
      UUID.fromString("11111111-1111-1111-1111-111111111111"),
      bookingRequestFrom,
      bookingRequestTo,
      "guest@example.com",
      "Website"
    )

  def createTableReadyTransactor[F[_]: Async: Logger](cfg: DbConfig): Resource[F, Transactor[F]] = {
    def deleteTable(transactor: Transactor[F]) =
      for {
        _ <- BookingSchema.deleteBookingTables(transactor, cfg)
        _ <- Logger[F].info(s"Table ${cfg.bookingTable} deleted")
      } yield ()

    for {
      transactor <- DbTransactor.make[F](cfg)
      _ <- BookingSchema.ensureBookingTables[F](transactor, cfg).toResource.onFinalize(deleteTable(transactor))
      _ <- Logger[F].info(s"Table ${cfg.bookingTable} created").toResource
    } yield transactor
  }

  test("Simple read and write from bookings table") {
    createTableReadyTransactor[IO](dbConfig).use { transactor =>
      for {
        storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
        now = Instant.now()
        _ <- IO.sleep(10.millis) //so now isBefore createdAt in booking
        b1res <- storage.createBooking(bookingRequest)
        _ = assert(b1res.isDefined)

        bookings <- storage.getHomeBookings(BookingsFilters(bookingRequest.homeId, None, None))
        _ = assert(bookings.size == 1)
        _ = assert(bookings.head.homeId == bookingRequest.homeId)
        _ = assert(bookings.head.fromDate == bookingRequest.fromDate)
        _ = assert(bookings.head.toDate == bookingRequest.toDate)
        _ = assert(bookings.head.source == bookingRequest.source)
        _ = assert(bookings.head.guestEmail == bookingRequest.guestEmail)
        _ = assert(bookings.head.createdAt.isAfter(now))
      } yield ()
    }
  }

  test("Constrain check -- overlapping bookings is not possible") {
    createTableReadyTransactor[IO](dbConfig).use { transactor =>
      for {
        storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
        b1res <- storage.createBooking(bookingRequest)
        _ = assert(b1res.isDefined)

        b2res <- storage.createBooking(bookingRequest.copy(guestEmail = "second@example.com"))
        _ = assert(b2res.isEmpty)

        bookings <- storage.getHomeBookings(BookingsFilters(bookingRequest.homeId, None, None))
        _ = assert(bookings.size == 1)
        _ = assert(bookings.head.guestEmail == bookingRequest.guestEmail)
      } yield ()
    }
  }

  test("Constrain check -- overlapping bookings for different homes are possible") {
    createTableReadyTransactor[IO](dbConfig).use { transactor =>
      for {
        storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
        b1res <- storage.createBooking(bookingRequest)
        _ = assert(b1res.isDefined)

        secondHoneId = UUID.fromString("11111111-1111-1111-1111-111111111112")
        b2res <- storage.createBooking(bookingRequest.copy(homeId = secondHoneId))
        _ = assert(b2res.isDefined)

        bookings1 <- storage.getHomeBookings(BookingsFilters(bookingRequest.homeId, None, None))
        _ = assert(bookings1.size == 1)
        _ = assert(bookings1.head.id == b1res.get)

        bookings2 <- storage.getHomeBookings(BookingsFilters(secondHoneId, None, None))
        _ = assert(bookings2.size == 1)
        _ = assert(bookings2.head.id == b2res.get)
      } yield ()
    }
  }

  test("Constrain check -- overlapping two bookings with one day is possible") {
    createTableReadyTransactor[IO](dbConfig).use { transactor =>
      for {
        storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
        b1res <- storage.createBooking(bookingRequest)
        _ = assert(b1res.isDefined)

        b2res <- storage.createBooking(bookingRequest.copy(fromDate = bookingRequest.toDate))
        _ = assert(b2res.isDefined)

        bookings1 <- storage.getHomeBookings(BookingsFilters(bookingRequest.homeId, None, None))
        _ = assert(bookings1.size == 2)
      } yield ()
    }
  }

  test("Constrain check -- overlapping race") {
    createTableReadyTransactor[IO](dbConfig).use { transactor =>
      for {
        storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
        _ <- List.fill(100)(storage.createBooking(bookingRequest)).parSequence_.void
        bookings1 <- storage.getHomeBookings(BookingsFilters(bookingRequest.homeId, None, None))
        _ = assert(bookings1.size == 1)
      } yield ()
    }
  }

  test("Constrain violation toDate less than fromDate") {
    createTableReadyTransactor[IO](dbConfig).use { transactor =>
      for {
        storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
        reversingRequest = bookingRequest.copy(fromDate = bookingRequest.toDate, toDate = bookingRequest.fromDate)
        _ <- interceptIO[PSQLException](storage.createBooking(reversingRequest))
      } yield ()
    }
  }

  test("Simple read and write from booking conflicts table") {
    val testConflict = BookingConflictData(
      homeId = UUID.randomUUID(),
      fromDate = LocalDate.of(2025, 8, 15),
      toDate = LocalDate.of(2025, 8, 17),
      guestEmail = "guest@example.com",
      source = "Website",
      createdAt = Instant.now()
    )

    val prog = createTableReadyTransactor[IO](dbConfig).use { transactor =>
      for {
        storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
        id <- storage.saveBookingConflict(testConflict)
        retrieved <- sql"""
        SELECT home_id, from_date, to_date, guest_email, source, created_at
        FROM ${Fragment.const(dbConfig.bookingConflictTable)}
        WHERE id = $id
      """.query[BookingConflictData].unique.transact(transactor)
      } yield (testConflict, retrieved)
    }

    prog.map { case (original, fetched) =>
      //we need custom equals because different precision: nanoseconds in Java, microseconds in Postgres
      assert(fetched === original)
    }
  }
    test("Writing is idempotent into booking_conflict table") {
      val testConflict = BookingConflictData(
        homeId = UUID.randomUUID(),
        fromDate = LocalDate.of(2025, 8, 15),
        toDate = LocalDate.of(2025, 8, 17),
        guestEmail = "guest@example.com",
        source = "Website",
        createdAt = Instant.now()
      )

      val prog = createTableReadyTransactor[IO](dbConfig).use { transactor =>
        for {
          storage <- new PostgresBookingStorage[IO](transactor, dbConfig).pure[IO]
          id1 <- storage.saveBookingConflict(testConflict)
          id2 <- storage.saveBookingConflict(testConflict)

          retrieved1 <- sql"""
          SELECT home_id, from_date, to_date, guest_email, source, created_at
          FROM ${Fragment.const(dbConfig.bookingConflictTable)}
          WHERE id = $id1
          """.query[BookingConflictData].option.transact(transactor)

          retrieved2 <- sql"""
          SELECT home_id, from_date, to_date, guest_email, source, created_at
          FROM ${Fragment.const(dbConfig.bookingConflictTable)}
          WHERE id = $id2
          """.query[BookingConflictData].option.transact(transactor)
        } yield (retrieved1, retrieved2)
      }

      prog.map { case (retrieved1, retrieved2) =>
        //we need custom equals because different precision: nanoseconds in Java, microseconds in Postgres
        assert(retrieved1.isDefined)
        assert(retrieved2.isEmpty)
      }
    }

}
