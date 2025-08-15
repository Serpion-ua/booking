package application

import graphql.mutation.CreateBooking

trait BookingConflictProducer[F[_]] {
  def processConflict(input: CreateBooking.Input): F[Unit]
}
