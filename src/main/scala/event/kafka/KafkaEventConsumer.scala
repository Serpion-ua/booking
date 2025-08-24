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
            consumer.stream //TODO use partitioning for faster processing, handler could be really slow
              .evalMap { committable =>
                for {
                  _ <- Logger[F].info(s"Received for topic ${cfg.topicName}: $committable")
                  _ <- handler(committable.record.value)
                    .handleErrorWith { e =>
                      Logger[F].error(e)(s"Failed to process record: ${committable.record}") //TODO error handling
                    }
                  _ <- committable.offset.commit
                } yield ()
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
