package persistence.postgres

import application.DbConfig
import cats.effect.Async
import cats.implicits._
import doobie._
import doobie.implicits._
import org.typelevel.log4cats.Logger

object BookingSchema {
  def ensureBookingTables[F[_]: Async: Logger](xa: Transactor[F], dbConfig: DbConfig): F[Unit] = {
    val bookingsTableName: String = dbConfig.bookingTable
    val bookingConflictsTableName: String = dbConfig.bookingConflictTable

    def createExtension: F[Unit] =
      sql"CREATE EXTENSION IF NOT EXISTS btree_gist".update.run
        .transact(xa)
        .flatMap(_ => Logger[F].info("Extension btree_gist ensured"))

    def createBookingsTable: F[Unit] =
      Fragment
        .const(
          s"""
         CREATE TABLE IF NOT EXISTS $bookingsTableName (
           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
           home_id UUID NOT NULL,
           from_date DATE NOT NULL,
           to_date DATE NOT NULL,
           guest_email TEXT NOT NULL,
           source TEXT NOT NULL,
           created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
           CONSTRAINT ${bookingsTableName}_valid_dates CHECK (from_date <= to_date),
           CONSTRAINT ${bookingsTableName}_no_overlap
              EXCLUDE USING gist (
              home_id WITH =,
              daterange(from_date, to_date, '[)') WITH &&
              )
         )
       """
        )
        .update
        .run
        .transact(xa)
        .flatMap(_ => Logger[F].info(s"Table '$bookingsTableName' ensured"))

    def buildBookingsIndex: F[Unit] =
      Fragment
        .const(
        s"""CREATE INDEX IF NOT EXISTS ${bookingsTableName}_home_from_to_idx
           ON $bookingsTableName (home_id, from_date, to_date)"""
        )
        .update
        .run
        .transact(xa)
        .flatMap(_ => Logger[F].info(s"Index '${bookingConflictsTableName}_home_from_to_idx' ensured"))

    def createBookingConflictsTable: F[Unit] =
      Fragment
        .const(
          s"""
         CREATE TABLE IF NOT EXISTS $bookingConflictsTableName (
           idempotency_key TEXT NOT NULL,
           id BIGSERIAL PRIMARY KEY,
           home_id UUID NOT NULL,
           from_date DATE NOT NULL,
           to_date DATE NOT NULL,
           guest_email TEXT NOT NULL,
           source TEXT NOT NULL,
           created_at TIMESTAMPTZ NOT NULL
          )
       """
        )
        .update
        .run
        .transact(xa)
        .flatMap(_ => Logger[F].info(s"Table '$bookingConflictsTableName' ensured"))

    def buildBookingConflictsIndex: F[Unit] =
      Fragment
        .const(
          s"""CREATE UNIQUE INDEX IF NOT EXISTS ${bookingConflictsTableName}_idem_key_idx
           ON $bookingConflictsTableName(idempotency_key)"""
        )
        .update
        .run
        .transact(xa)
        .flatMap(_ => Logger[F].info(s"Index '${bookingConflictsTableName}_home_from_to_idx' ensured"))

    for {
      _ <- createExtension
      _ <- createBookingsTable
      _ <- buildBookingsIndex
      _ <- createBookingConflictsTable
      _ <- buildBookingConflictsIndex
    } yield ()
  }

  def deleteBookingTables[F[_]: Async](transactor: Transactor[F], dbConfig: DbConfig): F[Unit] = {
    val sql =
      s"""
          DROP TABLE IF EXISTS ${dbConfig.bookingTable}, ${dbConfig.bookingConflictTable}
         """.stripMargin

    Update[Unit](sql)
      .run(())
      .transact(transactor)
      .void
  }
}
