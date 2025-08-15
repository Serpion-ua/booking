package http

import application.HttpConfig
import cats.effect._
import cats.effect.unsafe.IORuntime
import cats.implicits.toFunctorOps
import com.comcast.ip4s.{Host, Port}
import graphql.GraphQLSchema
import io.circe.Json
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.typelevel.log4cats.Logger
import sangria.catseffect.execution.IOExecutionScheme._
import sangria.execution.Executor
import sangria.marshalling.circe._
import sangria.parser.QueryParser

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object HttpServer {

  private def routes(context: GraphQLSchema.Context[IO])(implicit IoRuntime: IORuntime, logger: Logger[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "graphql" =>
      for {
        json <- req.as[Json]
        queryStr = json.hcursor.get[String]("query").getOrElse("")
        vars = json.hcursor.get[Json]("variables").toOption.getOrElse(Json.obj())
        response <- QueryParser.parse(queryStr) match {
          case Success(ast) =>
            Executor
              .execute(GraphQLSchema.schema, ast, context, variables = vars)
              .attempt
              .flatMap {
                case Right(resultJson) => Ok(resultJson)
                case Left(error) =>
                  logger.error(s"Error: ${error.getMessage}") >>
                    BadRequest(formatError(error))
              }
          case Failure(err) => BadRequest(Json.obj("error" -> Json.fromString(err.getMessage)))
        }
      } yield response
    }

  def create(config: HttpConfig, context: GraphQLSchema.Context[IO])(implicit
      logger: Logger[IO],
      IoRuntime: IORuntime
  ): Resource[IO, Unit] =
    org.http4s.ember.server.EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(config.host).get)
      .withPort(Port.fromInt(config.port).get)
      .withHttpApp(routes(context).orNotFound)
      .build
      .void

  private def formatError(error: Throwable): Json = error match {
    case syntaxError: sangria.parser.SyntaxError =>
      Json.obj(
        "errors" -> Json.arr(
          Json.obj(
            "message" -> Json.fromString(syntaxError.getMessage),
            "locations" -> Json.arr(
              Json.obj(
                "line" -> Json.fromBigInt(syntaxError.originalError.position.line),
                "column" -> Json.fromBigInt(syntaxError.originalError.position.column)
              )
            )
          )
        )
      )
    case NonFatal(e) => formatError(e.getMessage)
    case e           => throw e
  }

  private def formatError(message: String): Json =
    Json.obj(
      "errors" -> Json.arr(Json.obj("message" -> Json.fromString(message)))
    )
}
