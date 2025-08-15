package event.kafka

import application.{ApplicationConfig, KafkaConfig}
import cats.effect._
import model.BookingConflict
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration._

class KafkaProducerConsumerIntegrationTest extends CatsEffectSuite {
  implicit val logger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO]("KafkaProducerConsumerIntegrationTest")

  val config: ApplicationConfig = ConfigSource.default.loadOrThrow[ApplicationConfig]
  val baseKafkaCfg: KafkaConfig = config.kafka

  test("producer + consumer should exchange booking conflict messages") {
    val topic = s"${baseKafkaCfg.topicName}-itest-${UUID.randomUUID().toString}"
    val kafkaCfg = baseKafkaCfg.copy(topicName = topic)

    val bookingFrom = LocalDate.parse("2025-08-10")
    val bookingTo = LocalDate.parse("2025-08-12")
    val now = Instant.now()

    val sample1 = BookingConflict(UUID.randomUUID(), bookingFrom, bookingTo, "guest1@example.com", "website", now)
    val sample2 = BookingConflict(UUID.randomUUID(), bookingFrom, bookingTo, "guest2@example.com", "website", now)
    val sample3 = BookingConflict(UUID.randomUUID(), bookingFrom, bookingTo, "guest3@example.com", "website", now)

    val expected = 3

    val resources: Resource[IO, (event.EventProducer[IO], Ref[IO, Vector[BookingConflict]], Deferred[IO, Unit])] =
      for {
        _ <- Resource.eval(KafkaSchema.ensureTopic[IO](kafkaCfg)).onFinalize(KafkaSchema.deleteTopic[IO](kafkaCfg))

        // place to collect consumed messages and a signal for when we're done
        ref <- Resource.eval(Ref.of[IO, Vector[BookingConflict]](Vector.empty))
        done <- Resource.eval(Deferred[IO, Unit])

        producer <- KafkaEventProducer.make[IO](kafkaCfg)

        _ <- KafkaEventConsumer.make[IO](kafkaCfg).startConsumingBookingConflicts { conflict =>
          for {
            _ <- Logger[IO].info(s"consumer handler received: $conflict")
            _ <- ref.update(_ :+ conflict)
            cur <- ref.get
            _ <- if (cur.size >= expected) done.complete(()).void else Async[IO].unit
          } yield ()
        }
      } yield (producer, ref, done)

    resources.use { case (producer, ref, done) =>
      for {
        // small pause to ensure consumer subscription happens before producing
        _ <- IO.sleep(1.second)

        _ <- producer.produceBookConflictEvent(sample1)
        _ <- producer.produceBookConflictEvent(sample2)
        _ <- producer.produceBookConflictEvent(sample3)

        _ <- done.get.timeoutTo(10.seconds, IO.raiseError(new RuntimeException("Timed out waiting for Kafka messages")))

        collected <- ref.get
        _ <- IO {
          assertEquals(collected.size, expected)
          assertEquals(collected, Seq(sample1, sample2, sample3))
        }
      } yield ()
    }
  }
}
