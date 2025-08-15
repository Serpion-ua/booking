package event

import cats.effect.Async
import fs2.kafka.{Deserializer, Serializer}
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import model.BookingConflict

package object kafka {
  implicit val conflictEncoder: Encoder[BookingConflict] = deriveEncoder
  implicit val conflictDecoder: Decoder[BookingConflict] = deriveDecoder

  implicit def valueSerializer[F[_]: Async]: Serializer[F, BookingConflict] =
    Serializer.string[F].contramap(_.asJson.noSpaces)

  implicit def valueDeserializer[F[_]: Async]: Deserializer[F, BookingConflict] =
    Deserializer.string[F].map { jsonStr =>
      decode[BookingConflict](jsonStr) match {
        case Right(value) => value
        case Left(err)    => throw new RuntimeException(s"Failed to decode JSON: ${err.getMessage}")
      }
    }
}
