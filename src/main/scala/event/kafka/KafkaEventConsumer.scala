package event.kafka

import application.KafkaConfig
import cats.effect.Async
import cats.effect.implicits.{effectResourceOps, genSpawnOps}
import cats.syntax.all._
import event.EventConsumer
import fs2.kafka.Deserializer._
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, KafkaConsumer}
import model.BookingConflict
import org.typelevel.log4cats.Logger

object KafkaEventConsumer {
  def make[F[_]: Async: Logger](cfg: KafkaConfig): EventConsumer[F] = { (handler: BookingConflict => F[Unit]) =>
    {
      val consumerSettings: ConsumerSettings[F, String, BookingConflict] =
        ConsumerSettings[F, String, BookingConflict]
          .withBootstrapServers(cfg.bootstrapConnection)
          .withGroupId(cfg.groupId)
          .withClientId("client-id")
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withEnableAutoCommit(false)

      KafkaConsumer
        .resource(consumerSettings)
        .evalTap(_.subscribeTo(cfg.topicName))
        .flatMap { consumer =>
          Logger[F].debug("Start booking conflict consumer fiber").toResource >>
            consumer.stream
              .evalMap { committable =>
                Logger[F].info(s"Received for topic ${cfg.topicName}: $committable") >>
                  handler(committable.record.value) >>
                  committable.offset.commit
              }
              .compile
              .drain
              .background
              .onFinalize(Logger[F].info("Booking conflict consumer fiber had been stopped"))
        }
        .map(_.void)
    }
  }
}
