ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val doobieVersion = "1.0.0-RC10"
lazy val http4sVersion = "0.23.30"
lazy val circeVersion = "0.14.14"
lazy val pureconfigVersion = "0.17.9"
lazy val catsEffectVersion = "3.6.3"
lazy val fs2KafkaVersion = "3.9.0"

scalacOptions ++= Seq(
  "-deprecation","-feature","-Xlint","-Ywarn-dead-code","-Wvalue-discard"
)

Compile / mainClass := Some("Main")

resolvers += "Confluent Maven Repository" at "https://packages.confluent.io/maven/"

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

lazy val root = (project in file("."))
  .settings(
    name := "abooking",
    libraryDependencies ++= Seq(
      // Cats Effect
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "cats-effect-std" % catsEffectVersion,
      // HTTP4s
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      // Circe
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-generic-extras" % "0.14.4",
      // GraphQL (Sangria)
      "org.sangria-graphql" %% "sangria" % "4.2.11",
      "org.sangria-graphql" %% "sangria-circe" % "1.3.2",
      "org.sangria-graphql" %% "sangria-cats-effect-experimental" % "4.2.11",
      // Logging
      "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      // Config
      "com.github.pureconfig" %% "pureconfig-core" % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-generic" % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-cats-effect" % pureconfigVersion,
      // Database
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      // Kafka
      "com.github.fd4s" %% "fs2-kafka" % fs2KafkaVersion,
      "com.github.fd4s" %% "fs2-kafka-vulcan" % fs2KafkaVersion,
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.1.0" % Test,
      "org.tpolecat" %% "doobie-scalatest" % doobieVersion % Test,
      "org.mockito" %% "mockito-scala" % "2.0.0" % Test,
      "org.mockito" %% "mockito-scala-cats" % "2.0.0" % Test
    )
  )
