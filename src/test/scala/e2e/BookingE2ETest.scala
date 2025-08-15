package e2e

import application.ApplicationConfig
import cats.effect._
import doobie._
import doobie.implicits._
import graphql.mutation.CreateBooking.CreateBookingFailure.{CreateBookingAlternativeDates, CreateBookingConflict}
import graphql.mutation.CreateBooking.{CreateBookingResult, CreateBookingSuccess}
import graphql.query.GetHomeBookings.{GetHomeBookingsResult, GetHomeBookingsSuccess}
import io.circe.generic.auto._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, Json}
import model.Booking
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.circe._
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import persistence.postgres.DbTransactor
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration._

class BookingE2ETest extends CatsEffectSuite {
  implicit val logger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO]("e2e.BookingE2ESpec")

  val config: ApplicationConfig = ConfigSource.default.loadOrThrow[ApplicationConfig]

  val httpUriBase: Uri = Uri.unsafeFromString(s"http://${config.http.host}:${config.http.port}/graphql")

  val createBookingMutation: String =
    """
      mutation CreateBooking($booking: BookingInput!) {
        createBooking(bookingRequest: $booking) {
          __typename
          ... on CreateBookingSuccess { bookingId }
          ... on CreateBookingIncorrectRequest { description }
          ... on CreateBookingConflict {
            alternativeDates { fromDate toDate }
          }
        }
      }
    """

  def createBookingVars(homeId: String, from: String, to: String, guest: String, source: String): Json =
    Json.obj(
      "booking" -> Json.obj(
        "homeId" -> Json.fromString(homeId),
        "fromDate" -> Json.fromString(from),
        "toDate" -> Json.fromString(to),
        "guestEmail" -> Json.fromString(guest),
        "source" -> Json.fromString(source)
      )
    )

  val getHomeBookingsQuery: String =
    """
    query GetHomeBookings($input: GetHomeBookingsInput!) {
      getHomeBookings(input: $input) {
        __typename
        ... on GetHomeBookingsSuccess {
          bookings {
            id
            homeId
            fromDate
            toDate
            guestEmail
            source
            createdAt
          }
        }
        ... on GetHomeBookingsIncorrectRequest {
          description
        }
      }
    }
    """

  def getHomeBookingsVars(homeId: String): Json =
    Json.obj("input" -> Json.obj("homeId" -> Json.fromString(homeId)))

  def runGraphQLRequest(client: org.http4s.client.Client[IO], query: String, vars: Json): IO[Json] = {
    val reqJson = Json.obj("query" -> Json.fromString(query), "variables" -> vars)
    val req = Request[IO](Method.POST, httpUriBase)
      .withEntity(reqJson)
      .withHeaders(Header.Raw(CIString("Content-Type"), "application/json"))

    client.run(req).use { resp =>
      resp.as[Json].flatMap { json =>
        if (resp.status.isSuccess) IO.pure(json)
        else IO.raiseError(new RuntimeException(s"HTTP ${resp.status.code}: ${json.noSpaces}"))
      }
    }
  }

  implicit val localDateDecoder: Decoder[LocalDate] =
    Decoder.decodeString.emap { str =>
      try Right(LocalDate.parse(str, DateTimeFormatter.ISO_DATE))
      catch { case _: Exception => Left(s"Invalid date: $str") }
    }
  implicit val instantDecoder: Decoder[Instant] =
    Decoder.decodeString.emap { str =>
      try {
        Right(Instant.parse(str))
      } catch {
        case _: Exception => Left(s"Invalid Instant format: $str")
      }
    }
  implicit val configExtra: Configuration = Configuration.default.withDiscriminator("__typename") // GraphQL's union/tag field
  implicit val bookingDecoder: Decoder[Booking] = deriveConfiguredDecoder
  implicit val bookingDecoder2: Decoder[CreateBookingResult] = deriveConfiguredDecoder
  implicit val getHomeBookingsDecoder: Decoder[GetHomeBookingsResult] = deriveConfiguredDecoder

  // small polling helper to wait for condition (used to wait for DB write by the conflict consumer)
  def retryWithBackoff[A](maxAttempts: Int, delay: FiniteDuration)(fa: IO[Option[A]]): IO[Option[A]] = {
    def loop(attemptsLeft: Int): IO[Option[A]] =
      fa.flatMap {
        case some @ Some(_)           => IO.pure(some)
        case None if attemptsLeft > 0 => IO.sleep(delay) >> loop(attemptsLeft - 1)
        case None                     => IO.pure(None)
      }
    loop(maxAttempts)
  }

  test("create booking, verify listing, duplicate -> conflict, conflict persisted") {
    val homeId = UUID.randomUUID().toString
    val from = "2025-08-20"
    val to = "2025-08-22"
    val toDate = LocalDate.parse(to)
    val guest = s"guest-${UUID.randomUUID().toString}@example.com"
    val source = "test-suite"

    EmberClientBuilder.default[IO].build.use { client =>
      for {
        // 1) create booking
        createJson <- runGraphQLRequest(client, createBookingMutation, createBookingVars(homeId, from, to, guest, source))
        createdBookingEither1 = createJson.hcursor.downField("data").downField("createBooking").as[CreateBookingSuccess]
        _ <- IO(assert(createdBookingEither1.isRight, "Failed to parse first createBooking response"))
        bookingId = createdBookingEither1.toOption.get.bookingId
        _ <- logger.info(s"CreateBooking succeeded, bookingId = $bookingId")

        // 2) getHomeBookings and check the booking is present
        getJson <- runGraphQLRequest(client, getHomeBookingsQuery, getHomeBookingsVars(homeId))
        bookingsEither = getJson.hcursor.downField("data").downField("getHomeBookings").as[GetHomeBookingsSuccess]
        _ <- IO(assert(bookingsEither.isRight, "Failed to parse get bookings response"))
        bookings = bookingsEither.toOption.get.bookings

        _ <- IO(assert(bookings.size == 1, "Get more than one booking"))
        // find booking with same guest email
        found = bookings.exists { b =>
          b.guestEmail == guest &&
          b.fromDate.toString == from &&
          b.toDate.toString == to
        }
        _ <- IO(assert(found, "Created booking not found in getHomeBookings response"))
        _ <- logger.info(s"Get bookings succeeded. Found: $bookings")

        // 3) try to create same booking again -> expect conflict response with alternative dates
        create2Json <- runGraphQLRequest(client, createBookingMutation, createBookingVars(homeId, from, to, guest, source))
        createdBookingEither2 = create2Json.hcursor.downField("data").downField("createBooking").as[CreateBookingConflict]
        _ <- IO(assert(createdBookingEither2.isRight, "Failed to parse second createBooking response"))
        createBooking2 = createdBookingEither2.toOption.get
        //in case of the same booking (if it is only booking) we return to-to as alternative
        _ <- IO(assert(createBooking2.alternativeDates.contains(CreateBookingAlternativeDates(toDate, toDate))))

        _ <- logger.info(s"Second create produced expected $createBooking2")

        // 4) getHomeBookings and check the booking is still one
        get2Json <- runGraphQLRequest(client, getHomeBookingsQuery, getHomeBookingsVars(homeId))
        bookings2Either = get2Json.hcursor.downField("data").downField("getHomeBookings").as[GetHomeBookingsSuccess]
        _ <- IO(assert(bookings2Either.isRight, "Failed to parse get bookings response"))
        bookings2 = bookings2Either.toOption.get.bookings

        _ <- IO(assert(bookings2.size == 1, "Get more than one booking"))
        // find booking with same guest email
        found = bookings2.exists { b =>
          b.guestEmail == guest &&
            b.fromDate.toString == from &&
            b.toDate.toString == to
        }
        _ <- IO(assert(found, "Created booking not found in getHomeBookings response"))
        _ <- logger.info(s"Get bookings second time succeeded. Found: $bookings")

        // 5) check booking_conflicts table, conflict data shall be present
        transactorResource = DbTransactor.make[IO](config.db)

        resultOpt <- transactorResource.use { xa =>
          // function to check count of conflicts for the guest
          def checkOnce: IO[Option[Int]] =
            sql"""
              SELECT COUNT(*) FROM ${Fragment.const(config.db.bookingConflictTable)}
              WHERE guest_email = $guest
            """.query[Int].option.transact(xa)

          // retry up to 10 times with 500ms delay
          retryWithBackoff(maxAttempts = 10, 500.millis)(checkOnce)
        }

        _ <- resultOpt match {
          case Some(count) if count == 1 =>
            logger.info(s"Found $count conflict rows for guest $guest in ${config.db.bookingConflictTable}")
          case Some(0) =>
            IO.raiseError(new RuntimeException("Conflict not persisted in DB (count == 0)"))
          case Some(n) =>
            IO.raiseError(new RuntimeException(s"Conflict had been written more than one: $n"))
          case None =>
            IO.raiseError(new RuntimeException("Failed reading booking_conflicts table or timed out"))
        }

      } yield ()
    }
  }
}
