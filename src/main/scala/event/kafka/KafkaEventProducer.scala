package event.kafka

import application.KafkaConfig
import cats.effect.implicits.genSpawnOps
import cats.effect.std.Queue
import cats.effect.{Async, Resource}
import cats.syntax.all._
import event.EventProducer
import fs2.Stream
import fs2.kafka._
import model.BookingConflict
import org.typelevel.log4cats.Logger

object KafkaEventProducer {
  def make[F[_]: Async: Logger](cfg: KafkaConfig): Resource[F, EventProducer[F]] = {
    val producerSettings: ProducerSettings[F, String, BookingConflict] =
      ProducerSettings[F, String, BookingConflict]
        .withBootstrapServers(cfg.bootstrapConnection)
        .withClientId("client-id")

    def startProducer(queue: Queue[F, BookingConflict]): Resource[F, Unit] =
      KafkaProducer
        .resource(producerSettings)
        .flatMap { producer =>
          Stream
            .fromQueueUnterminated(queue)
            .parEvalMapUnorderedUnbounded { conflict =>
              val key = conflict.homeId.toString
              val record = ProducerRecord(cfg.topicName, key, conflict)
              producer.produce(ProducerRecords.one(record)).flatten.attempt.flatMap {
                case Left(e)  => Logger[F].error(e)(s"Failed to produce conflict $conflict")
                case Right(_) => Logger[F].debug(s"Produced conflict: $conflict")
              }
            }
            .compile
            .drain
            .background
            .void
        }
        .onFinalize(Logger[F].info("Producer fiber had been finished"))

    for {
      queue <- Resource.eval(Queue.unbounded[F, BookingConflict])
      _ <- startProducer(queue)
    } yield new EventProducer[F] {
      override def produceBookConflictEvent(conflict: BookingConflict): F[Unit] = {
        Logger[F].debug(s"Produce booking conflict event: $conflict") >>
          queue.offer(conflict)
      }
    }
  }
}
