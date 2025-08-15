import application.ApplicationConfig
import application.impl.BookingConflictHandlerEvent
import application.impl.{BookingConflictProducerEvent, BookingHandlerImpl}
import cats.effect._
import cats.effect.unsafe.IORuntime
import doobie.Transactor
import event.kafka.{KafkaEventConsumer, KafkaEventProducer, KafkaSchema}
import graphql.GraphQLSchema.Context
import http.HttpServer
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}
import org.typelevel.log4cats.{Logger, LoggerFactory}
import persistence.postgres.{BookingSchema, DbTransactor, PostgresBookingStorage}
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax.CatsEffectConfigSource

object Main extends ResourceApp.Forever {
  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  implicit val logger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO]("Booking Server")

  private def loadConfig[F[_]: Sync]: F[ApplicationConfig] = {
    ConfigSource.default.loadF[F, ApplicationConfig]()
  }

  private def cleanUp(config: ApplicationConfig, transactor: Transactor[IO]): IO[Unit] = {
    if (config.app.cleanUpOnStart) {
      for {
        _ <- logger.warn("GOING TO DELETE ALL DATA. Flag app.clean-up-on-start is set to true")
        _ <- KafkaSchema.deleteTopic[IO](config.kafka)
        _ <- BookingSchema.deleteBookingTables[IO](transactor, config.db)
      } yield ()
    } else { IO.unit }
  }

  override def run(args: List[String]): Resource[IO, Unit] = for {
    config <- loadConfig[IO].toResource
    transactor <- DbTransactor.make[IO](config.db)
    _ <- cleanUp(config, transactor).toResource

    persistence <- PostgresBookingStorage.make[IO](transactor, config.db)

    conflictHandler = new BookingConflictHandlerEvent(persistence)

    _ <- KafkaSchema.ensureTopic[IO](config.kafka).toResource
    eventProducer <- KafkaEventProducer.make[IO](config.kafka)
    _ <- KafkaEventConsumer
      .make[IO](config.kafka)
      .startConsumingBookingConflicts(conflictHandler.handleBookingConflicts)

    conflictProducer = new BookingConflictProducerEvent[IO](eventProducer)
    bookingHandler <- BookingHandlerImpl.make[IO](persistence, conflictProducer).toResource

    context = Context[IO](bookingHandler)
    _ <- HttpServer.create(config.http, context)
  } yield ExitCode.Success
}
