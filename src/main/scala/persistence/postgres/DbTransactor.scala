package persistence.postgres

import application.DbConfig
import cats.effect._
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor

object DbTransactor {
  def make[F[_]: Async](cfg: DbConfig): Resource[F, HikariTransactor[F]] = {
    for {
      hikariConfig <- Resource.pure {
        val config = new HikariConfig()
        config.setDriverClassName("org.postgresql.Driver")
        config.setJdbcUrl(s"jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.name}")
        config.setUsername(cfg.user)
        config.setPassword(cfg.password)
        config
      }
      xa <- HikariTransactor.fromHikariConfig[F](hikariConfig)
    } yield xa
  }
}
