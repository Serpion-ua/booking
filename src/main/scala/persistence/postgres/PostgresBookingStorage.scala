package persistence.postgres

import application.DbConfig
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import model.Booking
import org.typelevel.log4cats.Logger
import persistence.BookingStorage
import persistence.BookingStorage.{BookingConflictData, BookingData, BookingsFilters}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.matching.Regex

object PostgresBookingStorage {
  def make[F[_]: Async: Logger](transactor: Transactor[F], dbConfig: DbConfig): Resource[F, BookingStorage[F]] = {
    for {
      _ <- BookingSchema.ensureBookingTables[F](transactor, dbConfig).toResource
      persistence = new PostgresBookingStorage[F](transactor, dbConfig)
    } yield persistence
  }
}

class PostgresBookingStorage[F[_]](xa: Transactor[F], dbConfig: DbConfig)(implicit F: Sync[F]) extends BookingStorage[F] {

  private val bookingsTableName: String = dbConfig.bookingTable
  private val bookingConflictsName: String = dbConfig.bookingConflictTable

  // Very simple whitelist for identifier safety (letters, digits, underscore)
  private val safeName: Regex = """^[a-zA-Z0-9_]+$""".r

  require(
    safeName.pattern.matcher(bookingsTableName).matches(),
    s"Unsafe table name: $bookingsTableName. Only [A-Za-z0-9_] are allowed."
  )

  require(
    safeName.pattern.matcher(bookingConflictsName).matches(),
    s"Unsafe table name: $bookingConflictsName. Only [A-Za-z0-9_] are allowed."
  )

  private val bookingsTableFragment: Fragment = Fragment.const(bookingsTableName)
  private val bookingConflictsTableFragment: Fragment = Fragment.const(bookingConflictsName)

  override def createBooking(request: BookingData): F[Option[UUID]] = {
    //save current time immediately not on db level
    val createdAt = Instant.now()

    val insertFrag: Fragment =
      fr"INSERT INTO " ++ bookingsTableFragment ++ fr""" (
        id, home_id, from_date, to_date, guest_email, source, created_at
      ) VALUES (
        gen_random_uuid(),
        ${request.homeId},
        ${request.fromDate},
        ${request.toDate},
        ${request.guestEmail},
        ${request.source},
        $createdAt
      ) RETURNING id
      """

    insertFrag
      .query[UUID]
      .option
      .transact(xa)
      .attempt
      .flatMap {
        case Right(optId)                                                           => F.pure(optId)
        case Left(e: org.postgresql.util.PSQLException) if e.getSQLState == "23P01" =>
          // exclusion_violation (overlap) -> treat as "booking conflict"
          F.pure(None)
        case Left(other) =>
          F.raiseError(other)
      }
  }

  implicit val bookingRead: Read[Booking] =
    Read[(Booking.Id, Booking.HomeId, LocalDate, LocalDate, String, String, Instant)].map {
      case (id, homeId, fromDate, toDate, guestEmail, source, createdAt) =>
        Booking(id, homeId, fromDate, toDate, guestEmail, source, createdAt)
    }

  override def getHomeBookings(request: BookingsFilters): F[Seq[Booking]] = {
    val base: Fragment =
      fr"SELECT id, home_id, from_date, to_date, guest_email, source, created_at FROM " ++ bookingsTableFragment ++
        fr" WHERE home_id = ${request.homeId}"

    val fromFilter: Fragment = request.fromDate match {
      case Some(fd) => fr" AND to_date >= $fd" // return bookings that end on/after fd
      case None     => Fragment.empty
    }
    val toFilter: Fragment = request.toDate match {
      case Some(td) => fr" AND from_date <= $td" // bookings that start on/before td
      case None     => Fragment.empty
    }

    val finalFrag: Fragment = base ++ fromFilter ++ toFilter ++ fr" ORDER BY from_date"

    finalFrag
      .query[Booking]
      .to[Seq]
      .transact(xa)
      .attempt
      .flatMap {
        case Right(xs) => F.pure(xs)
        case Left(e)   => F.raiseError(e) // rethrow; caller may handle
      }
  }

  override def saveBookingConflict(request: BookingConflictData): F[Option[Long]] = {
    val insertFrag: Fragment =
      fr"INSERT INTO " ++ bookingConflictsTableFragment ++ fr""" (
        idempotency_key, home_id, from_date, to_date, guest_email, source, created_at
      ) VALUES (
        ${request.idempotencyKey},
        ${request.homeId},
        ${request.fromDate},
        ${request.toDate},
        ${request.guestEmail},
        ${request.source},
        ${request.createdAt}
      )
      ON CONFLICT (idempotency_key) DO NOTHING
      RETURNING id
      """

    insertFrag
      .query[Long]
      .option
      .transact(xa)
      .attempt
      .flatMap {
        case Right(id)   => F.pure(id)
        case Left(other) => F.raiseError(other)
      }
  }

}
