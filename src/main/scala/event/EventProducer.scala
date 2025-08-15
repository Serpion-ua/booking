package event

import model.BookingConflict

trait EventProducer[F[_]] {
  def produceBookConflictEvent(conflict: BookingConflict): F[Unit]
}
