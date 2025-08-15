package application.impl

import application.BookingConflictProducer
import cats.effect.Async
import event.EventProducer
import graphql.mutation.CreateBooking

class BookingConflictProducerEvent[F[_]: Async](eventProducer: EventProducer[F]) extends BookingConflictProducer[F] {
  override def processConflict(input: CreateBooking.Input): F[Unit] = {
    eventProducer.produceBookConflictEvent(input.toConflict) //@TODO handle error
  }
}
