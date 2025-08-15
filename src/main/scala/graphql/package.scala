import sangria.ast
import sangria.ast.StringValue
import sangria.schema.ScalarType
import sangria.validation.ValueCoercionViolation

import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.util.{Failure, Success, Try}

package object graphql {

  case object UUIDCoercionViolation extends ValueCoercionViolation("Invalid UUID format")

  implicit val UUIDType: ScalarType[UUID] = ScalarType[UUID](
    name = "UUID",
    description = Some("A UUID value"),
    coerceOutput = (value, _) => value.toString,
    coerceUserInput = {
      case s: String => Try(UUID.fromString(s)).toEither.left.map(_ => UUIDCoercionViolation)
      case _         => Left(UUIDCoercionViolation)
    },
    coerceInput = {
      case ast.StringValue(s, _, _, _, _) => Try(UUID.fromString(s)).toEither.left.map(_ => UUIDCoercionViolation)
      case _                              => Left(UUIDCoercionViolation)
    }
  )

  // Custom violation
  case object DateCoercionViolation extends ValueCoercionViolation("Invalid date format, expected YYYY-MM-DD")

  // ScalarType for LocalDate
  implicit val LocalDateType: ScalarType[LocalDate] = ScalarType[LocalDate](
    name = "LocalDate",
    description = Some("A date in the format YYYY-MM-DD"),
    coerceOutput = (ld, _) => ld.format(DateTimeFormatter.ISO_LOCAL_DATE),
    coerceUserInput = {
      case s: String =>
        Try(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)) match {
          case Success(date) => Right(date)
          case Failure(_)    => Left(DateCoercionViolation)
        }
      case _ => Left(DateCoercionViolation)
    },
    coerceInput = {
      case StringValue(s, _, _, _, _) =>
        Try(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)) match {
          case Success(date) => Right(date)
          case Failure(_)    => Left(DateCoercionViolation)
        }
      case _ => Left(DateCoercionViolation)
    }
  )

  case object InstantCoercionViolation extends ValueCoercionViolation("Invalid ISO-8601 Instant value")

  implicit val InstantType: ScalarType[Instant] = ScalarType[Instant](
    name = "Instant",
    description = Some("A timestamp in ISO-8601 format (UTC)"),

    coerceUserInput = {
      case s: String => Try(Instant.parse(s)) match {
        case Success(i) => Right(i)
        case Failure(_) => Left(InstantCoercionViolation)
      }
      case _ => Left(InstantCoercionViolation)
    },

    coerceInput = {
      case StringValue(s, _, _, _, _) => Try(Instant.parse(s)) match {
        case Success(i) => Right(i)
        case Failure(_) => Left(InstantCoercionViolation)
      }
      case _ => Left(InstantCoercionViolation)
    },

    coerceOutput = (i, _) => i.toString
  )
}
