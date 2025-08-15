package application

import graphql.mutation.CreateBooking
import graphql.query.GetHomeBookings

trait BookingHandler[F[_]] {
  def createBooking(input: CreateBooking.Input): F[CreateBooking.CreateBookingResult]
  def getHomeBookings(input: GetHomeBookings.Input): F[GetHomeBookings.GetHomeBookingsResult]
}
