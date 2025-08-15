# How to Run

## Prerequisites
1. Installed **Java**, **sbt**, **Docker**
2. `docker-compose.yaml` has been launched by Docker

---

## Run Server
```bash
sbt run
```

- This will start the GraphQL server.
- **Important:** By default, all data from persistence and Kafka will be **deleted** during launch.
- To change this behavior, set the parameter:

```hocon
app.clean-up-on-start = false
```

---

## Run End-to-End Test Flow
```bash
sbt "testOnly e2e.BookingE2ETest"
```

- This will run a client that connects to the server and performs the following steps:
    1. Create a booking
    2. Call `getHomeBookings` and check the booking is present
    3. Try to create the same booking again → expect conflict response with alternative dates
    4. Call `getHomeBookings` and check the booking is still only one
    5. Check `booking_conflicts` table; conflict data should be present

---

## Notes
- Original GraphQL schema has been changed:
    - Instead of `_: String! # Format: YYYY-MM-DD`, **LocalDate** is used in schema definition
    - The query `bookings(homeId: ID!): [Booking!]!` has been changed to `bookings(homeId: UUID!): [Booking!]!`