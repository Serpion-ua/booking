package graphql.query

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe.generic.auto._
import model.Booking
import model.Booking.HomeId
import sangria.macros.derive.deriveObjectType
import sangria.marshalling.circe._
import sangria.schema.{Argument, Field, InputField, InputObjectType, ListType, ObjectType, UnionType, fields}
import graphql._

object GetHomeBookings {
  case class Input(homeId: HomeId)

  sealed trait GetHomeBookingsResult
  //TODO return not whole booking but just some information about booking
  case class GetHomeBookingsSuccess(bookings: Seq[Booking]) extends GetHomeBookingsResult
  sealed trait GetHomeBookingsFailure extends GetHomeBookingsResult
  object GetHomeBookingsFailure {
    case class GetHomeBookingsIncorrectRequest(description: String) extends GetHomeBookingsFailure
  }

  val name: String = "getHomeBookings"
  val argumentName: String = "homeId"

  private val getBookingInputType: InputObjectType[Input] = InputObjectType[Input](
    "GetHomeBookingsInput",
    "Input for GetHomeInput",
    List(InputField("homeId", UUIDType))
  )
  private val argument: Argument[Input] = Argument("input", getBookingInputType)

  implicit val bookingDerivation: ObjectType[Unit, Booking] = deriveObjectType[Unit, Booking]()

  private val getHomeBookingsSuccessType: ObjectType[Unit, GetHomeBookingsSuccess] = ObjectType(
    "GetHomeBookingsSuccess",
    fields[Unit, GetHomeBookingsSuccess](
      Field(
        name = "bookings",
        fieldType = ListType(bookingDerivation),
        resolve = _.value.bookings
      )
    )
  )

  private val getHomeBookingsIncorrectRequestType =
    deriveObjectType[Unit, GetHomeBookingsFailure.GetHomeBookingsIncorrectRequest]()

  private val getBookingResultType = UnionType(
    "GetHomeBookingsResult",
    List(getHomeBookingsSuccessType, getHomeBookingsIncorrectRequestType)
  )

  def field(implicit ioRuntime: IORuntime): Field[GraphQLSchema.Context[IO], Unit] =
    Field(
      name = "getHomeBookings",
      fieldType = getBookingResultType,
      arguments = List(argument),
      resolve = ctx => {
        val input = ctx.arg[Input]("input")
        ctx.ctx.bookingHandler.getHomeBookings(input).unsafeToFuture()
      }
    )
}
