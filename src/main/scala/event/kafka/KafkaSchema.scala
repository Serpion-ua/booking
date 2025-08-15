package event.kafka

import application.KafkaConfig
import cats.effect.Async
import cats.syntax.all._
import fs2.kafka.{AdminClientSettings, KafkaAdminClient}
import org.apache.kafka.clients.admin.NewTopic
import org.typelevel.log4cats.Logger

object KafkaSchema {
  def ensureTopic[F[_]: Async: Logger](config: KafkaConfig): F[Unit] = {
    val adminSettings = AdminClientSettings(config.bootstrapConnection)
    KafkaAdminClient.resource(adminSettings).use { adminClient =>
      val createTopicConfig = new NewTopic(
        config.topicName,
        config.partitions,
        config.replicationFactor.toShort
      )
      adminClient
        .createTopic(createTopicConfig)
        .flatMap { _ => Logger[F].info(s"Topic $config.topicName created successfully") }
        .handleErrorWith { error => Logger[F].info(s"Failed to create topic: ${error.getMessage}") }
    }
  }

  def deleteTopic[F[_]: Async: Logger](config: KafkaConfig): F[Unit] = {
    val adminSettings = AdminClientSettings(config.bootstrapConnection)
    KafkaAdminClient.resource(adminSettings).use { adminClient =>
      adminClient
        .deleteTopic(config.topicName)
        .flatMap { _ => Logger[F].info(s"Topic ${config.topicName} deleted successfully") }
        .handleErrorWith { error => Logger[F].error(s"Failed to delete topic: ${error.getMessage}") }
    }
  }

}
