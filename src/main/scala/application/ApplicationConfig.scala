package application

final case class DbConfig(
    host: String,
    port: Int,
    user: String,
    password: String,
    name: String,
    bookingTable: String,
    bookingConflictTable: String
)

final case class AppConfig(
    cleanUpOnStart: Boolean
)

final case class HttpConfig(host: String, port: Int)

final case class ApplicationConfig(
    db: DbConfig,
    app: AppConfig,
    http: HttpConfig,
    kafka: KafkaConfig
)

case class KafkaConfig(
    host: String,
    bootstrapServersPrefix: String,
    bootstrapServersPort: Int,
    schemaRegistryPrefix: String,
    schemaRegistryPort: Int,
    topicName: String,
    partitions: Int,
    replicationFactor: Int,
    groupId: String
) {
  val bootstrapConnection: String =
    s"$bootstrapServersPrefix://$host:$bootstrapServersPort"
}
