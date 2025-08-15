package event

import cats.effect.Resource
import model.BookingConflict

trait EventConsumer[F[_]] {
  def startConsumingBookingConflicts(consumer: BookingConflict => F[Unit]): Resource[F, F[Unit]]
}
