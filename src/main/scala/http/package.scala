import cats.effect.Concurrent
import io.circe.{Decoder, Json}
import org.http4s.EntityDecoder
import org.http4s.circe.jsonOf

package object http {

  case class GraphQLRequest(
      query: String,
      variables: Option[Json] = None,
      operationName: Option[String] = None
  )

  object GraphQLRequest {
    import io.circe.generic.semiauto._
    implicit val decoder: Decoder[GraphQLRequest] = deriveDecoder
    implicit def entityDecoder[F[_]: Concurrent]: EntityDecoder[F, GraphQLRequest] =
      jsonOf[F, GraphQLRequest]
  }
}
