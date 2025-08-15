package graphql.mutation

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import graphql.mutation.CreateBooking.CreateBookingFailure._
import graphql.{GraphQLSchema, LocalDateType, UUIDType}
import io.circe.generic.auto._
import model.Booking._
import sangria.macros.derive.deriveObjectType
import sangria.marshalling.circe._
import sangria.schema._

object CreateBooking {

  case class Input(
      homeId: HomeId,
      fromDate: Date,
      toDate: Date,
      guestEmail: Email,
      source: Source
  )

  sealed trait CreateBookingResult
  case class CreateBookingSuccess(bookingId: Id) extends CreateBookingResult
  sealed trait CreateBookingFailure extends CreateBookingResult
  object CreateBookingFailure {
    case class CreateBookingAlternativeDates(fromDate: Date, toDate: Date)

    case class CreateBookingIncorrectRequest(description: String) extends CreateBookingFailure
    case class CreateBookingConflict(alternativeDates: Option[CreateBookingAlternativeDates]) extends CreateBookingFailure
  }
  val name: String = "createBooking"
  val argumentName: String = "bookingRequest"

  private val bookingInputType: InputObjectType[Input] =
    InputObjectType[Input](
      "BookingInput",
      "Input type for creating a booking",
      List(
        InputField("homeId", UUIDType),
        InputField("fromDate", LocalDateType),
        InputField("toDate", LocalDateType),
        InputField("guestEmail", StringType),
        InputField("source", StringType)
      )
    )
  private val argument = Argument(argumentName, bookingInputType)

  private val bookingSuccessType = deriveObjectType[Unit, CreateBookingSuccess]()
  private val incorrectRequestType: ObjectType[Unit, CreateBookingIncorrectRequest] =
    deriveObjectType[Unit, CreateBookingIncorrectRequest]()

  implicit val alternativeDatesType: ObjectType[Unit, CreateBookingAlternativeDates] =
    deriveObjectType[Unit, CreateBookingAlternativeDates]()
  private val bookingConflictType: ObjectType[Unit, CreateBookingConflict] =
    deriveObjectType[Unit, CreateBookingConflict]()

  private val bookingResultType = UnionType(
    "CreateBookingResult",
    List(bookingSuccessType, incorrectRequestType, bookingConflictType)
  )

  def field(implicit ioRuntime: IORuntime): Field[GraphQLSchema.Context[IO], Unit] = {
    Field(
      name = name,
      fieldType = bookingResultType,
      arguments = List(argument),
      resolve = ctx => {
        val input: Input = ctx.arg[Input](argumentName)
        ctx.ctx.bookingHandler.createBooking(input).unsafeToFuture()
      }
    )
  }

}
