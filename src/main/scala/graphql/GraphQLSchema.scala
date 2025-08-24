package graphql

import application.BookingHandler
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import graphql.mutation.CreateBooking
import graphql.query.GetHomeBookings
import sangria.schema._

object GraphQLSchema {
  case class Context[F[_]](bookingHandler: BookingHandler[F])

  private def mutationType(implicit runtime: IORuntime): ObjectType[Context[IO], Unit] = {
    ObjectType(
      "Mutation",
      fields[Context[IO], Unit](
        CreateBooking.field
      )
    )
  }

  private def queryType(implicit runtime: IORuntime): ObjectType[Context[IO], Unit] =
    ObjectType(
      "Query",
      fields[Context[IO], Unit](
        GetHomeBookings.field
      )
    )

  def schema(implicit runtime: IORuntime): Schema[Context[IO], Unit] =
    Schema(queryType, Option(mutationType))
}
