# 🎟️ Ticket Booking System (BookMyShow / Ticketmaster)

Complete System Design Notes: Requirements → Estimation → Entities → DB and API → Preventing Double Booking → HLD → Flows → Cache and Scaling → Failures → Java LLD with UML and tested code → Concurrency practice → Follow-up questions → 30-second answer

Think of **BookMyShow, Ticketmaster or an airline seat map**:

```
Browse events → pick a show → see the seat map → select seats
        ↓
Seats are HELD for you for ~10 minutes
        ↓
Pay
        ↓
Seats become BOOKED, you get a ticket (QR code)
```

## Index

1. Problem Statement
2. Functional Requirements
3. Non-Functional Requirements (with the CAP trade-off)
4. Capacity Estimation
5. Core Entities
6. Database Design
7. API Design (with idempotency)
8. The Core Decision: Preventing Double Booking
9. High-Level Architecture
10. Flows
11. Caching
12. Database Scaling
13. Feature Deep Dives
14. Final Production Architecture
15. Java LLD
16. Concurrency Practice
17. Failure Scenarios
18. Advanced (Interview Bonus) Topics
19. Interview Follow-Up Questions
20. Extra Topics: Study List
21. Java Practice Roadmap
22. Final 30-Second Interview Explanation

---

## 1. Problem Statement

Design a service where users can:

1. Browse events (movies, concerts, matches) by city, date and category.
2. Open a show and see its **seat map** with live availability.
3. Select one or more seats and **hold** them for a short time (for example 10 minutes) while they pay.
4. Pay, and get a confirmed booking with a ticket.
5. Cancel a booking and get a refund.
6. Get notifications (booking confirmed, payment failed, hold about to expire).

What this problem is really about: **many people compete for a small, fixed inventory** (each seat can be sold exactly once), and traffic is **very spiky** (a popular concert goes on sale and a million people arrive in the same minute). Selling the same seat twice is the one failure that cannot be allowed, so correctness matters more than raw speed.

**Out of scope (say this out loud in the interview):** dynamic pricing, seat recommendations, loyalty points, organiser dashboards and food ordering. Mention them as extensions at the end.

## 2. Functional Requirements

### 2.1 Search and browse

```
GET events by city, date, category, language
→ list of events and their shows (time, venue, price range)
```

### 2.2 View seat map

```
For a show: every seat with its state: AVAILABLE, HELD (by someone) or BOOKED
```

A slightly stale seat map is acceptable. The final decision is always made when the user tries to hold the seat.

### 2.3 Hold seats

```
Input:  showId, list of seatNos (for example A1, A2)
Output: bookingId in PENDING state, holdExpiresAt
```

- **All or nothing:** if the user asks for 4 seats and only 3 are free, they get none of them. Holding a partial set is useless.
- A hold lasts a fixed time and then disappears by itself.
- State out loud: max seats per booking (say 6) and hold duration (say 10 minutes).

### 2.4 Pay and confirm

```
Input:  bookingId, payment details (through a payment gateway)
Output: booking CONFIRMED and a ticket, or a clear failure
```

### 2.5 Cancel and refund

User cancels a confirmed booking, seats go back on sale, money is refunded according to the policy.

### 2.6 Notifications

Email/SMS/push for confirmation, failure and cancellation.

### Questions to settle with the interviewer

- If the payment succeeds **after** the hold expired, do we try to re-take the seats, or refund? (Safe default: re-take if still free, otherwise refund.)
- Do we need reserved seating, or also general admission (just a count of tickets)? We design reserved seating, which is the harder one. (Both terms are explained just below.)

### Reserved seating vs general admission

These are the two ways an event can sell tickets, and they change the design.

**Reserved seating** means every ticket is for a **specific seat**, like seat A12 in a cinema, a stadium or an airplane. You pick the exact seat on a seat map, and that seat can be sold to only one person. Each seat is its own item of inventory, so two people can fight over the *same* seat, and we must protect each seat separately (this is the hold-a-seat and no-double-booking problem in section 8).

**General admission (GA)** means a ticket gets you **in the door, not a particular seat**. Think of a standing concert floor, a festival or a free-seating hall: the venue sells, say, 5,000 tickets and everyone just finds a spot. There is no seat map and no seat number. The inventory is simply **a count** (tickets remaining), and the question becomes "are there at least N tickets left?"

The same idea applies to both: check and change must happen as one atomic step. For reserved seating that is a conditional update on a seat row ("hold A12 only if it is free"). For general admission it is a conditional update on a counter ("subtract N only if at least N remain"), which is shown in section 18.7. Reserved seating is the harder case because there are many individual items that users can pick in combinations (and want together, like 4 seats side by side), while GA has one number to protect. Many events mix both, for example reserved seats in the stands and general admission on the floor, so a real system supports both ticket types.

## 3. Non-Functional Requirements

### Consistency on the write path (the most important one)

A seat must **never** be sold to two bookings. This is a **consistency requirement on writes**: when two people try to hold the same seat at the same moment, exactly one must win and the other must be told clearly that it failed.

### Availability

Browsing and the seat map should be up almost always. Booking should be highly available too, but if we must choose, we prefer to refuse a booking than to sell a seat twice.

### Low latency

Search and seat map p99 under about 200 ms. (**p99** means 99% of requests finish at least this fast. It shows the slow tail that an average hides.) Hold under about 500 ms.

### Scalability and spikes

Average load is small, but a hot event can create a spike 100 times larger on a single show. The system must survive the spike without falling over.

### Fairness

For a hot event, people expect roughly first-come, first-served.

### Durability

A confirmed booking and a payment must never be lost.

### Security

Bots buying tickets to resell, fraud, and payment data (we never store card details ourselves; the payment gateway does).

### CAP trade-off: decide it while writing the NFRs

**CAP** says that when the network between parts of a system breaks (a *partition*), you must choose between **consistency** (everyone sees the same, latest data) and **availability** (every request still gets an answer). Decide it per operation:

- **Search and seat map (reads): availability first (AP).** Showing a seat as free when it was just taken a second ago is fine, because the hold step will catch it.
- **Hold seats and confirm booking (writes): consistency first (CP).** If the database that owns the seats cannot be reached or agree, we return an error and the user retries. We never guess.
- **Notifications and analytics:** eventual consistency is fine.

## 4. Capacity Estimation

State assumptions first, then calculate. Here we start from **DAU** (Daily Active Users: distinct users who use the product in a day), because a booking system is user-centric: traffic grows with the number of users. (For a URL shortener, most traffic comes from people who are not our users, so we started from writes per month instead.)

### Assumptions

```
DAU                         = 10 million
Requests per user per day   = 10   (search, view show, view seat map)
Bookings per day            = 1 million
Seats per booking           = 2.5 on average
Venues                      = 5,000, each with ~5 screens and 4 shows a day
Seats per show              = 200
Peak factor                 = 3x for browsing, 10x for bookings (evening and weekends)
```

### Traffic

**QPS** means *Queries Per Second*: how many requests the system gets every second. Average QPS is requests per day divided by 86,400 seconds in a day. We design for the **peak**, which is average times a peak factor.

```
Read requests/day   = 10M × 10                 = 100 million
Average read QPS    = 100M / 86,400            = ~1,160 per second
Peak read QPS       = 1,160 × 3                = ~3,500 per second

Booking QPS         = 1M / 86,400              = ~12 per second
Peak booking QPS    = 12 × 10                  = ~120 per second
Hold attempts       = 3 per booking → ~35/sec average
```

### Storage

```
Bookings in 5 years  = 1M × 365 × 5            = 1.8 billion
Storage              = 1.8B × 1 KB             = ~1.8 TB

Shows per day        = 5,000 × 5 × 4           = 100,000
ShowSeat rows/day    = 100,000 × 200           = 20 million
Active inventory     = 20M × 30 days           = 600 million rows × 100 B = ~60 GB
```

Only the next \~30 days of seats need to live in the hot database. Old shows are archived.

### Concurrent holds

```
At peak: 120 bookings/sec × 600 sec (10 minute hold) = ~70,000 holds at the same time
```

That is small. A hold is a few fields on a seat row, so this is not a storage problem.

### The real challenge: one hot event

```
Hot concert: 50,000 seats, 1,000,000 people want in → 20 people per seat

If everyone refreshes the seat map every 5 seconds:
1,000,000 / 5 = 200,000 requests per second on ONE show
```

That is 60 times our normal *peak for the whole system*. This is why the design needs seat-map caching, push updates instead of polling, and a **waiting room** (a queue that lets people in at a controlled rate).

### How many servers?

Servers needed = **peak QPS ÷ QPS one server can handle**. With 3,500 peak QPS and about 1,000 QPS per server, we need \~4 servers for browsing. Because availability needs redundancy even at low load, we still run at least 3 instances across zones. The hot event is what pushes us to add capacity, caches and the waiting room.

**Conclusion:** average load is easy. The hard parts are **correctness under contention** (no double booking) and **spikes** on a single show.

## 5. Core Entities

```
User
Event        (a movie, concert, match)
Show         (one screening or performance of an Event at a Venue and time)
ShowSeat     (one seat in ONE show: the thing people fight for)
Booking      (a user's order for one or more seats)
Payment      (the money side of a booking)
```

```
User 1 ───< * Booking 1 ─── 1 Payment
Event 1 ───< * Show 1 ───< * ShowSeat
Booking * ─── 1 Show
ShowSeat * ─── 0..1 Booking    (a seat is held or booked by at most one booking)
```

Notice **ShowSeat** versus a plain "Seat": the physical seat A1 of a screen is the same every day, but A1 for the 7 pm show and A1 for the 10 pm show are different things to sell. So we keep a seat layout template per screen and create one **ShowSeat** row per seat per show, each with its own status.

## 6. Database Design

### 6.1 Tables

```
users
-------------------------
user_id        UUID PK
name, email (UNIQUE), phone
created_at

events
-------------------------
event_id       BIGINT PK
name, category, language, description

shows
-------------------------
show_id        BIGINT PK
event_id       FK
venue_id       FK
start_time     TIMESTAMP

show_seats
-------------------------
show_id        FK                 ┐ composite
seat_no        VARCHAR            ┘ PRIMARY KEY (show_id, seat_no)
price          DECIMAL
status         ENUM(AVAILABLE, HELD, BOOKED)
booking_id     UUID NULL
hold_expires_at TIMESTAMP NULL
version        INT                (for optimistic locking, explained later)

bookings
-------------------------
booking_id     UUID PK
user_id        FK
show_id        FK
status         ENUM(PENDING, CONFIRMED, CANCELLED, EXPIRED)
total_amount   DECIMAL
hold_expires_at TIMESTAMP
created_at

payments
-------------------------
payment_id        UUID PK
booking_id        FK
amount, status    ENUM(SUCCESS, FAILED, REFUNDED)
idempotency_key   VARCHAR UNIQUE
gateway_ref       VARCHAR
created_at
```

### 6.2 Why these primary keys

- **`show_seats`: composite key `(show_id, seat_no)`, not a separate auto-increment id.** Every lookup is "this seat in this show", so the key we search by is the primary key (one lookup, no secondary index). It also puts all seats of one show next to each other in the index, and when we shard by `show_id` all seats of a show live on one shard. That matters because holding four seats at once must be a single-shard transaction.
- **`bookings`: UUID, not a sequential number.** Any server can generate it without asking a central counter, and it cannot be guessed (someone could otherwise try `booking_id = 1001, 1002...` to look at other people's tickets).
- **`payments.idempotency_key` is UNIQUE.** This is what makes a retried payment safe (explained in section 7).

### 6.3 SQL or NoSQL?

Ask the questions that drive the choice, then answer for this system:

1. **What is the access pattern?** Hold several seats together, all or nothing, with a condition ("only if still free"). That is a **multi-row atomic update with a condition**.
2. **Do we need transactions?** Yes. A **transaction** is a group of database steps that either all happen or none do (**ACID**: Atomic, Consistent, Isolated, Durable). Holding 4 seats, creating the booking row, and recording the payment must not end up half-done.
3. **Is there money involved?** Yes, so correctness and an audit trail beat raw scale.
4. **How big is the hot data?** About 60 GB of active seats and \~1.8 TB of bookings over 5 years. That fits a sharded relational database comfortably.
5. **What queries do we need?** "My bookings", "all seats of this show", reports. These are natural SQL.

**Recommended answer: a relational database (PostgreSQL or MySQL) for the booking core**, sharded by `show_id`. Use other stores around it for the parts that fit them better: Elasticsearch for event search, Redis for caches and the waiting room, Kafka for events.

This is the opposite of the URL shortener, where a key-value store fit best. The lesson is that you choose from the access pattern, not from a favourite technology.

> **How to say it:** "The core operation is holding several seats together, all or nothing, only if they are still free. That needs multi-row transactions and conditional updates, and there is money involved, so I use a relational database with ACID guarantees, sharded by show id so one show's seats live on one shard. Search goes to Elasticsearch and hot reads go through a cache, because those do not need transactions."

**Pushback you may get:**

- *"Does SQL scale for a flash sale?"* The load on any single show is limited by how fast one shard can do conditional updates, and these are short, indexed, single-row statements. A shard can do thousands per second, and the waiting room keeps us from exceeding that.
- *"Why not DynamoDB?"* It is possible (it has conditional writes and limited transactions), and it scales effortlessly, but multi-item transactions are limited in size and cost more, and our reporting and relational queries get harder. If the team already runs DynamoDB well, it is a defensible choice.
- *"What about NewSQL (Spanner, CockroachDB)?"* A good answer for multi-region with strong consistency, at the price of higher write latency and cost.

### 6.4 Indexes

- Primary keys as above.
- `show_seats (show_id, status)` for "how many seats are free in this show".
- `show_seats (hold_expires_at)` where status = HELD, for the cleanup job.
- `bookings (user_id, created_at)` for "my bookings".
- `shows (event_id, start_time)`; city and date search is served by Elasticsearch.

## 7. API Design

### Browse and seat map

```
GET /api/v1/events?city=Bengaluru&date=2026-10-12&category=MOVIE
GET /api/v1/events/{eventId}/shows
GET /api/v1/shows/{showId}/seats
```

### Hold seats

```
POST /api/v1/shows/{showId}/holds
{ "seatNos": ["A1", "A2"] }
```

`201 Created` with `{ bookingId, expiresAt, totalAmount }`. `409 Conflict` if any seat is not available (and none are held). `400` if more than the allowed seats are requested.

### Pay

```
POST /api/v1/bookings/{bookingId}/pay
Idempotency-Key: 7f3c-91ab-...
{ "paymentMethodToken": "tok_..." }
```

`200` confirmed, `402` payment failed, `410 Gone` if the hold already expired.

### Payment webhook

```
POST /api/v1/payments/webhook      (called by the payment gateway)
```

A **webhook** is a request that the gateway sends *to us* when something happens (payment succeeded), instead of us asking repeatedly. Gateways retry webhooks, so we must handle the same one twice.

### Cancel and list

```
DELETE /api/v1/bookings/{bookingId}
GET    /api/v1/users/me/bookings
```

### Idempotency (making retries safe)

**What it means.** An operation is **idempotent** if doing it once or many times leaves the system in the same state. Networks fail and clients retry: the user taps *Pay*, the connection drops, the app (or the user) sends it again. Without idempotency we could **charge the card twice**.

**Where each operation stands:**

- `GET` requests (search, seat map, my bookings) are naturally idempotent.
- `DELETE` (cancel) is idempotent: the second call finds it already cancelled and the end state is the same.
- **Hold** is protected by the atomic condition itself, but a retry by the same user for seats they already hold should return their existing booking, not fail with `409`.
- **Pay is the dangerous one.** Fix: the client sends a unique `Idempotency-Key` (a UUID created once per payment attempt and reused on retries). The server stores `key → result`. If the same key arrives again, it returns the stored result and does **not** charge again. The `UNIQUE` constraint on `payments.idempotency_key` makes this safe even when two retries arrive at the same moment.
- **Webhooks** are de-duplicated by the gateway's payment id.

The `confirmBooking` code in the LLD section (section 15) does exactly this, and test 4 shows that a retry with the same key charges only once.

> **How to say it:** "Reads and deletes are naturally idempotent. For payments I accept an idempotency key and store the first result, enforced by a unique constraint, so a retry returns the same outcome and the card is charged once."

## 8. The Core Decision: Preventing Double Booking

This is the question the interviewer is really asking. Everything else (search, payments, notifications) is standard. The hard part is: **two users click the same seat at the same moment. Exactly one must get it.**

### 8.1 Why this is hard: the race condition

A **race condition** is a bug where the result depends on which of two operations happens to run first. Here is the naive way to book a seat:

```
1. read the seat:  is it AVAILABLE?
2. if yes, write:  status = BOOKED for me
```

Two users, same instant:

```
User A: read seat A1 → AVAILABLE
User B: read seat A1 → AVAILABLE        (A has not written yet)
User A: write A1 = BOOKED by A
User B: write A1 = BOOKED by B          ← overwrites A. Both think they own the seat.
```

The gap between step 1 and step 2 is the problem. The whole design is about **removing that gap**, so that "check" and "change" happen as one indivisible step. (In section 16 you will see this bug happen in real code: 100 threads fighting for one seat and several of them winning.)

### 8.2 Two ideas you need first

**A database lock.** A lock says "I am working on this row, everyone else wait". **Pessimistic locking** locks first and then works (assume conflict will happen). **Optimistic locking** works first and checks at the end that nobody else changed the row (assume conflict is rare), and retries or fails if someone did.

**A hold (a lease).** Between choosing a seat and paying, the user may take minutes. We cannot keep a database lock open that long. So instead we write a *hold* on the seat: status `HELD`, who holds it, and when it expires. It is like putting your bag on a cinema seat with a note saying "back by 7:10". Anyone can take the seat after the note expires.

### 8.2.1 Pessimistic vs optimistic locking, step by step

**Everyday picture.** Two people want to edit the same shared document. In the **pessimistic** way, the first person "checks out" the document: it is locked, the second person must wait until the first is done. In the **optimistic** way, both open it and edit their own copy at the same time, and when saving, the system checks whether the document changed since you opened it. If it did, your save is rejected and you must reload and redo your edit.

**Pessimistic locking** means: *lock first, then work*. You assume a conflict is likely, so you stop others from touching the row at all. In SQL this is `SELECT ... FOR UPDATE`. Two users, seat A1:

```
User A: BEGIN; SELECT status FROM show_seats WHERE seat='A1' FOR UPDATE
          → gets the lock, sees AVAILABLE
User B: BEGIN; SELECT status FROM show_seats WHERE seat='A1' FOR UPDATE
          → BLOCKED, has to wait for A
User A: UPDATE ... SET status='HELD' ; COMMIT
          → lock released
User B: (unblocked now) reads the row → sees HELD → fails
```

The conflict is **prevented** by making the second user wait. The cost is the waiting: if many people want the same seat they queue up, they use up database connections while waiting, and the locks can cause deadlocks (two transactions waiting for each other forever). The lock must be released quickly, which is why we never keep one open while the user pays.

**Optimistic locking** means: *work first, check at the end*. You assume a conflict is rare, so you do not lock anything. You read the row together with a **version number**, and when you save, you say "update only if the version is still the one I read".

```
User A: read A1 → AVAILABLE, version 4        (no lock)
User B: read A1 → AVAILABLE, version 4        (no waiting either)
User A: UPDATE ... SET status='HELD', version=5 WHERE seat='A1' AND version=4
          → 1 row updated → A wins
User B: UPDATE ... SET status='HELD', version=5 WHERE seat='A1' AND version=4
          → 0 rows updated (the version is 5 now) → B lost, must reload and retry
```

The conflict is **detected** at save time, and the loser is rejected. Nobody waits, but the loser's work was wasted. If conflicts are rare that is a good trade. If a hot seat has hundreds of people trying, most of them lose and retry, and the retries create even more load.

**The difference in one line:** pessimistic *stops* a conflict from happening by blocking others; optimistic *allows* the conflict and catches it at the end.

**When to use which.** Pessimistic fits short operations where conflicts are common and a wrong result is expensive (for example moving money between two accounts). Optimistic fits cases where conflicts are rare and users spend a long time between reading and saving (for example editing a profile page).

**What we do for seats.** We use neither in its pure form. The *atomic conditional update* in Approach 4 is optimistic in spirit (no lock is held while the user thinks, and nobody queues), but instead of a version number the condition is the business rule itself: "set to HELD *only if the seat is free right now*". The database checks and changes in one step, so exactly one user wins and the other gets "0 rows updated" immediately, with no retry loop. That is why it works well even for a popular seat.

### 8.3 Approaches compared

**Approach 1: Check, then update (broken).** The naive version above. Shown only so you can explain why it fails.

**Approach 2: Pessimistic row lock (`SELECT ... FOR UPDATE`).**

```sql
BEGIN;
SELECT status FROM show_seats
 WHERE show_id = 7 AND seat_no = 'A1' FOR UPDATE;    -- others wait here
-- if status is AVAILABLE:
UPDATE show_seats SET status = 'HELD', ... WHERE show_id = 7 AND seat_no = 'A1';
COMMIT;
```

- Pros: simple to reason about, correct.
- Cons: other requests wait in line on the same row, so a hot seat creates a queue and ties up database connections. If one transaction locks A1 then A2 while another locks A2 then A1, they wait for each other forever: a **deadlock**. The fix is to always lock seats in the same sorted order. Never hold such a lock while the user pays.

**Approach 3: Optimistic locking with a version number.**

```sql
-- read: seat A1, version = 4
UPDATE show_seats SET status = 'HELD', version = 5, ...
 WHERE show_id = 7 AND seat_no = 'A1' AND version = 4;
-- 1 row updated → I won.  0 rows → someone changed it, I lost.
```

- Pros: no lock is held while you think.
- Cons: for a seat many people want, most attempts fail and retry, wasting work. It is ideal when conflicts are rare, not for a hot seat.

**Approach 4: Conditional update with a hold lease (recommended).** Same idea as optimistic locking, but the *condition itself* is "is this seat free right now", so no version number or read is needed:

```sql
UPDATE show_seats
   SET status = 'HELD', booking_id = :bid,
       hold_expires_at = now() + interval '10 minutes'
 WHERE show_id = :show AND seat_no = :seat
   AND (status = 'AVAILABLE'
        OR (status = 'HELD' AND hold_expires_at < now()));   -- an expired hold counts as free

-- rows affected = 1 → the seat is yours.   0 → someone else has it.
```

The database runs this single statement atomically, so there is no gap between check and change. Exactly one of two simultaneous statements updates the row; the other sees it as already held and updates 0 rows. This is called an **atomic conditional update**. For several seats, update them all in one transaction and **roll back if the number of rows changed is less than the number requested**: all or nothing.

**Approach 5: Redis lock in front (`SET key value NX EX`).**

```
SET hold:show7:A1  bookingId  NX  EX 600      → OK means you got it
```

`NX` means "only set if it does not exist" and `EX 600` auto-deletes it after 600 seconds, so it is a hold with built-in expiry, and Redis can do hundreds of thousands of these per second.

- Pros: very fast, takes load off the database, expiry for free.
- Cons: it is a *second* source of truth. If Redis fails over and loses a key, two people may hold the same seat. And if a hold expires while the user is mid-payment, a second user can take the seat. So Redis can never be the final judge; the database conditional update still has to guard the real write. Use Redis as a **fast filter in front of the database** for hot shows, not instead of it.

**Approach 6: Waiting room and queue (admission control).** For flash sales, put users into a queue and let them in at a rate the system can handle. This does not make booking correct by itself. It protects the correct mechanism from being flooded (details in section 13).

### 8.4 Recommended design

> **Use Approach 4 as the source of truth (atomic conditional update with a hold lease in the database), optionally put Approach 5 in front for hot shows, and use Approach 6 for flash sales.**

Reasons, in order of importance:

1. **Correctness is enforced by the database**, in one atomic statement, so it holds even when servers crash, requests are retried, or caches are wrong.
2. **No lock is held while the user thinks or pays.** The lease expires by itself.
3. **Expiry is built into the condition** (`hold_expires_at < now()`), so a seat becomes available again at exactly the right moment, even if the cleanup job is late.
4. **It is simple to explain and test.** You can test it with 100 threads (section 16).

Use **the database's clock** (`now()`) for expiry, never the application servers' clocks, because servers' clocks drift apart and one server could decide a hold has expired when another thinks it is still valid.

### 8.5 Seat state machine

```
                 hold (conditional update)
   AVAILABLE ─────────────────────────────▶ HELD
       ▲                                      │  │
       │   hold expires / user cancels /      │  │ pay succeeds and
       │   payment fails                      │  │ hold still valid
       └──────────────────────────────────────┘  ▼
       ▲                                       BOOKED
       └──────────── booking cancelled ───────────┘
```

Booking states: `PENDING → CONFIRMED`, or `PENDING → EXPIRED / CANCELLED`, and `CONFIRMED → CANCELLED`. Writing the allowed moves down (and refusing all others) is the **State** idea and prevents bugs like confirming an already-expired booking.

### 8.6 Confirming after payment

```sql
UPDATE show_seats SET status = 'BOOKED'
 WHERE booking_id = :bid AND status = 'HELD' AND hold_expires_at >= now();
-- rows affected must equal the number of seats in the booking
```

If fewer rows change, the hold was lost while the user was paying. Then we **refund** (or try to re-hold if the seat is still free). To make this rare, extend the hold by a few minutes at the moment the user presses *Pay* (the "payment in progress" grace window), which is what real sites do.

### 8.7 What to say in the interview

> "The seat inventory has to be protected by the database, because caches and locks outside it can be wrong. When a user picks seats I do a single atomic conditional update: set the seat to HELD with an expiry, but only if it is currently available or its old hold has expired. If the number of rows updated is less than the number of seats requested, I roll back, so the hold is all or nothing. No lock is kept while the user pays; the hold is a lease that expires by itself using the database clock. After payment I run a second conditional update to move the seats from HELD to BOOKED, only if the hold is still valid, otherwise I refund. For hot events I put a Redis hold in front to absorb the load and a waiting room to control how many people reach the booking step, but the database stays the final judge."

**Pushback you may get:**

- *"Why not just use a distributed lock?"*

  **First, what is a distributed lock?** A normal lock only works inside one program. With many servers, we need a lock that all servers share, so it is kept in a shared place such as a Redis key (for example `lock:show7:A1`). Whoever creates the key owns the lock. To be safe against crashes, the lock has an **expiry time** (say 10 seconds), so if the owner dies, the lock disappears by itself and nobody waits forever.

  **The problem is that the expiry can pass while the owner is still working.** A server can freeze for a while (a long garbage-collection pause, a slow network call, a busy CPU) and not notice that time has passed. Here is what happens:

  ```
  Server A: takes the lock on seat A1 (expires in 10 s)
  Server A: freezes for 15 s (long pause or slow network)
     ... after 10 s the lock expires automatically ...
  Server B: takes the lock on A1 (it is free now) and writes: A1 belongs to booking B
  Server A: wakes up, still thinks it holds the lock, and writes: A1 belongs to booking A
            → A overwrites B. The seat is sold twice.
  ```

  The lock only decided *who may try*. It did not check at write time whether the write is still valid, and Server A did not know its lock had expired.

  **With the conditional update this cannot happen.** When Server A finally writes, it runs `UPDATE ... SET status='HELD' ... WHERE seat is free`. The seat is already held by B, so the database updates **0 rows** and A is told it failed. The database checks the *real current state* at the moment of the write, so it does not matter how slow A was or whether its lock expired.

  **Other weaknesses of a lock on its own:** if Redis fails over to a replica, a lock that was just granted can be lost, and then two servers both believe they hold it. Fixing this needs extra machinery (a fencing token, see section 18.2), which in the end means the database must still check the write.

  **So what do we do?** The database conditional update stays the final judge. A Redis lock (or `SET NX EX` hold) is still useful as a **speed-up in front**: it rejects most competing requests cheaply, so only a few reach the database. If that lock is ever wrong, the database check still prevents double booking.

  **Short answer to say in the interview:** "A distributed lock can expire while a slow server is still working, so two servers can both think they own the seat. I let the database make the final decision with a conditional update at write time, and use a lock only as an optimization in front."
- *"What if the hold expires while the user is paying?"* The confirm step re-checks the hold. If it was lost, we refund automatically. To reduce this, I extend the hold when the user presses Pay.
- *"What about a hot seat that thousands want?"* Most losers fail fast on one indexed row update, which is cheap. The waiting room limits the number reaching this step, and Redis can reject most of them before they touch the database.
- *"Optimistic or pessimistic?"* Neither in the pure form. The conditional update is optimistic (no lock held while thinking) but the condition is the business rule, not a version number, so there are no retry storms.

## 9. High-Level Architecture

```
                         ┌─────────────────────┐
                         │       CLIENT        │
                         │  Web / Mobile App   │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ CDN / DNS / WAF     │  static content, bot filtering
                         └──────────┬──────────┘
                                    ▼
                         ┌─────────────────────┐
                         │   Load Balancer     │
                         └──────────┬──────────┘
                                    ▼
                         ┌─────────────────────┐
                         │    API Gateway      │  auth, rate limit, waiting-room check
                         └──────────┬──────────┘
        ┌───────────────┬───────────┴────────────┬────────────────┐
        ▼               ▼                        ▼                ▼
 ┌─────────────┐ ┌──────────────┐        ┌──────────────┐ ┌──────────────┐
 │  Catalog /  │ │   Booking    │        │   Payment    │ │ Notification │
 │   Search    │ │  (inventory) │        │   Service    │ │   Service    │
 │   Service   │ │   Service    │        └──────┬───────┘ └──────▲───────┘
 └──────┬──────┘ └──────┬───────┘               │                │
        │               │                       ▼                │
        ▼               ▼                 Payment Gateway       Kafka
 Elasticsearch   Redis (holds, seat-map     (Razorpay, Stripe)     ▲
 + catalog DB    cache, waiting room)                              │
                        │                                          │
                        ▼                                          │
                 Booking DB (SQL, sharded by show_id)  ── events ──┘
                 primary + read replicas
                        ▲
                        │
                 Hold-expiry worker (background)
```

Why the services are separate: **Catalog/Search** is read-heavy and tolerant of staleness, so it can be cached and scaled freely. **Booking** is the small, critical, strongly consistent part and gets the most protection. **Payment** talks to an external provider and has its own failure modes. Splitting them lets each scale and fail independently.

## 10. Flows

### 10.1 Browse and view the seat map

```
Client → Gateway → Catalog Service → cache (Redis) → hit: return
                                                    → miss: Elasticsearch / DB → fill cache
Seat map: Booking Service → seat-map cache (1-2 s old) → miss: DB replica → fill cache
```

### 10.2 Hold seats

```
Client: POST /shows/7/holds {A1, A2}
   ↓
Gateway: authenticate, rate limit, waiting-room token valid?
   ↓
Booking Service:
   validate (≤ 6 seats, show not started)
   (optional) Redis: SET hold:7:A1 NX EX 600 for each seat → any fails → undo, 409
   BEGIN transaction on the show's shard
      conditional UPDATE each seat (or one UPDATE ... IN (...))
      rows updated < seats requested → ROLLBACK → 409 "seat taken"
      INSERT booking (PENDING, hold_expires_at)
   COMMIT
   ↓
Return bookingId + expiresAt  (client shows a countdown)
```

### 10.3 Pay and confirm

```
Client: POST /bookings/{id}/pay  (Idempotency-Key)
   ↓
Booking Service:
   booking PENDING and not expired?          no → 410 Gone
   extend hold by a short grace window
   Payment Service → gateway: charge (idempotency key)
        FAILED  → release seats, booking CANCELLED, 402
        SUCCESS ↓
   conditional UPDATE: HELD → BOOKED (only if hold still valid)
        rows < seats → hold lost → refund, booking EXPIRED, 410
        rows = seats ↓
   booking CONFIRMED, payment saved
   publish "BookingConfirmed" to Kafka → Notification Service sends ticket
   ↓
Return 200 + ticket
```

The gateway can also call our **webhook** ("payment succeeded") if our request timed out. The webhook handler runs the same confirm logic, so it must be idempotent.

### 10.4 Hold expiry

```
Lazy:       the next conditional UPDATE treats an expired hold as AVAILABLE (always correct).
Background: worker every few seconds:
              UPDATE show_seats SET status='AVAILABLE', booking_id=NULL
               WHERE status='HELD' AND hold_expires_at < now();
              mark the matching bookings EXPIRED, publish "HoldExpired".
```

### 10.5 Cancel

```
Booking CONFIRMED → seats back to AVAILABLE → refund via gateway (idempotent)
                  → publish "BookingCancelled" → notification
Booking PENDING   → release the hold, booking CANCELLED
```

## 11. Caching

Only cache what can tolerate being slightly stale, and **never decide a booking from a cache**.

- **Catalog (events, shows, venues):** changes rarely. Cache for minutes to hours. Cache-aside (read the cache, on a miss read the database and fill the cache).
- **Seat map:** changes constantly, but a view that is 1 or 2 seconds old is fine. Cache it very briefly, or better, update it by events: when a hold or booking changes a seat, publish an event and refresh the cached map. A client that picks a seat which is already gone just gets `409` from the hold step and the seat turns grey.
- **Holds and bookings:** never served from cache; the database is the authority.

### Cache layers

```
1. CDN / edge       – static content, and cached seat-map snapshots for very hot shows
2. Local in-process – inside each app server's own memory
3. Redis (shared)   – seat-map cache, holds for hot shows, waiting room
4. Database
```

**What is a local (in-process) cache?** A cache that lives inside your application's own memory (the JVM heap), in the same process as your code: a `HashMap`, an LRU map, or a library such as Caffeine. A read is a plain method call, with no network. Compared with Redis: it is much faster (microseconds versus about a millisecond for a network call), but each server has its **own private copy** (server A caching a seat map does not help server B), it must stay small, and it is empty after a restart. For a hot show it is very useful: a 1-second local copy of the seat map absorbs thousands of identical reads per second before they ever reach Redis.

### Cache problems to mention

1. **Stampede:** a hot cached item expires and thousands of requests hit the database at once. Fix: single-flight loading (one request refills, others wait), jittered TTLs.
2. **Hot key:** the seat map of the one hot show overloads one Redis node. Fix: local in-process cache and replicated copies.
3. **Stale data:** the seat map shows a free seat that is taken. This is acceptable by design, because the hold step is authoritative.
4. **Push instead of poll:** with 200,000 people polling every 5 seconds we create 200,000 requests per second. Using WebSockets or Server-Sent Events to push seat changes (or just a coarse "N seats left" counter) cuts this dramatically.

## 12. Database Scaling

### Replication

```
        Primary (all holds, confirms, bookings)
             │ replicates
     ┌───────┼───────┐
     ▼       ▼       ▼
  Replica  Replica  Replica   → seat-map reads, "my bookings", reports
```

Replicas are slightly behind (**replication lag**). Fine for the seat map. But a hold or confirm must **always go to the primary**, because it must see the latest state. And after a user books, show "my bookings" from the primary for a moment (**read-your-writes**), otherwise they may see their own booking missing.

### Sharding

3 TB over five years is not large, but write contention on hot shows and operational limits justify sharding.

**Shard by `show_id`.** All seats of a show, and the bookings for it, live on one shard. Holding 4 seats is then a normal single-shard transaction, with no distributed transaction needed. That is the deciding reason.

Why not other keys:

- **By `user_id`:** the seats a user wants are spread over shards, so a multi-seat hold would be a cross-shard operation.
- **By `seat_id`:** the seats of one booking land on different shards. Same problem.
- **By `event_id`:** a mega event with one show becomes one overloaded shard, and different shows of the same event cannot be spread.

**The hot-show problem.** Sharding by show means one popular show is a hot shard. Mitigations: give mega events a dedicated shard; split a big venue by section (each section is its own unit of inventory); and use the waiting room and Redis filter so that only a controlled rate reaches that shard.

### Consistent hashing

If you place shards with `hash(show_id) % N`, then adding a shard changes the result for almost every key and most data must move. **Consistent hashing** puts the shards and keys on a ring and assigns each key to the next shard clockwise, so adding a shard moves only about 1/N of the keys. Each server gets many points on the ring (**virtual nodes**) so the load stays even. In a test with 10,000 keys, going from 3 to 4 servers moved 74% of keys with `hash % N` and 26% with a ring of 100 virtual nodes per server. Managed databases (Cassandra, DynamoDB, Vitess and similar) do this for you.

### Archiving

Seats of shows that already happened are moved to cheap storage. This keeps the hot table at \~60 GB.

## 13. Feature Deep Dives

### 13.1 Waiting room (virtual queue) for flash sales

**Problem:** at 12:00 a million people hit *Book*. Even a perfect booking service cannot serve a million seat holds in the same second, and chaos is unfair.

**Idea:** put people in a line and let them into the booking step at a controlled rate.

```
User opens the sale page before it starts → joins the waiting room
   queue position = Redis sorted set (score = arrival time or a random draw)
Every few seconds the system admits the next N users:
   gives each a signed, short-lived admission token
Gateway: the hold endpoint refuses requests without a valid token
```

The admission rate (N per second) is chosen from what the booking shard can do. Many sites randomize the first minute (everyone who arrives during the first 60 seconds gets a random place) to be fair to slower connections. The queue is served mostly from the CDN and Redis, so a million waiting users cost very little.

### 13.2 Payments

- We never touch card numbers. The client sends card details to the **payment gateway**, which returns a token, and we pass that token. This keeps us out of the heaviest security compliance (PCI-DSS).
- The charge uses the idempotency key (section 7).
- **Reconciliation job:** a nightly or hourly job compares our payments with the gateway's records and fixes mismatches (charged but not confirmed, or confirmed but refunded). It is the safety net for lost webhooks.
- **Saga (the pattern for multi-step flows without one big transaction).** Booking involves our database and an external payment, which cannot be in one transaction. A **saga** is a sequence of steps where each step has a *compensating action* that undoes it: hold seats (undo: release), charge (undo: refund), confirm (undo: cancel and refund). If a step fails, we run the compensations of the earlier steps. Our pay flow in section 10.3 is a small saga.

### 13.3 Notifications

After confirmation we publish a `BookingConfirmed` event to Kafka. The Notification Service reads it and sends email, SMS and push. Booking never waits for notifications and a slow email provider cannot slow a booking. In code this is the **Observer** pattern (section 15). Use an **outbox**: write the event in the same database transaction as the booking, and a relay publishes it to Kafka, so an event is never lost if the app crashes between the database write and the publish.

### 13.4 Ticket and entry

The ticket is a **QR code** that contains a signed token (booking id plus a signature). The scanner at the venue verifies the signature and marks the ticket as used, so a screenshot cannot be used twice.

### 13.5 Search

Search by city, date, category and name is a text and filter query, served by **Elasticsearch**, kept in sync from the catalog database through a change feed. It is separate from the booking database so that heavy browsing never competes with bookings.

### 13.6 Rate limiting and bots

Ticket scalpers use bots. Defences: rate limit per user, IP and device on the hold endpoint (token bucket in Redis), CAPTCHA before joining the waiting room, limit seats per booking and per account for hot events, and require login with a verified phone or email.

### 13.7 Cancellation and refunds

Cancel is idempotent. A confirmed cancel frees the seats immediately (they become `AVAILABLE`), triggers a refund through the gateway with its own idempotency key, and publishes an event. The refund amount follows the policy (full, partial, none depending on time before the show).

## 14. Final Production Architecture

```
                        ┌───────────────────┐
                        │      CLIENT       │
                        └─────────┬─────────┘
                                  ▼
                        ┌───────────────────┐
                        │ DNS / CDN / WAF   │  seat-map snapshots, static, bot rules
                        └─────────┬─────────┘
                                  ▼
                        ┌───────────────────┐
                        │  Load Balancer    │
                        └─────────┬─────────┘
                                  ▼
                        ┌───────────────────┐
                        │   API Gateway     │  auth • rate limit • admission token
                        └─────────┬─────────┘
          ┌───────────────────────┼─────────────────────────────┐
          ▼                       ▼                             ▼
 ┌─────────────────┐    ┌───────────────────┐         ┌──────────────────┐
 │ Waiting Room    │    │ Catalog / Search  │         │ Booking Service  │
 │ (Redis queue)   │    │ ES + cache        │         │ hold • confirm   │
 └─────────────────┘    └───────────────────┘         │ cancel           │
                                                      └───┬──────────┬───┘
                                    local cache + Redis ◀─┘          │
                                    (seat map, hot holds)            ▼
                                                      ┌──────────────────────┐
                                                      │ Booking DB (SQL)     │
                                                      │ sharded by show_id   │
                                                      │ primary + replicas   │
                                                      └──────────┬───────────┘
        ┌──────────────────┐                                     │ outbox
        │ Payment Service  │◀── pay ── Booking Service           ▼
        │ ↔ Payment Gateway│                                   Kafka
        └──────────────────┘                          ┌──────────┼───────────┐
                                                      ▼          ▼           ▼
                                               Notification  Analytics   Search sync
                                                 Service                  (catalog)

        Hold-expiry worker ── scans HELD seats past their expiry ── releases them
        Reconciliation job ── compares our payments with the gateway
```

## 15. Java LLD (Concept & Coding style)

This section follows the learning order: **requirements → entities → relationships (UML) → design patterns → code → how the main flows run**. It is plain Java: no Spring Boot, no controllers, no real database. A `ConcurrentHashMap` plays the role of the database so you can run everything on your laptop.

### 15.1 Step 1: What must the code do?

- Hold one or more seats of a show for a limited time, **all or nothing**.
- Confirm the booking after a successful payment, and turn the held seats into booked seats.
- Reject a second user who wants a seat that is held or booked.
- Release seats when the payment fails, the booking is cancelled, or the hold expires.
- Charge a customer only once, even if the confirm request is repeated.
- Notify the user when a booking is confirmed or cancelled.
- Stay correct when many threads (and many servers) hit the same seat at once.

### 15.2 Step 2: Find the entities (the nouns)

Underline the nouns in the requirements: *user, event, show, seat, booking, payment*. Those are the entities from section 5. Everything else (service, repository, gateway, listener) is behaviour, not data, and comes later.

### 15.3 Step 3: Entities and their attributes

**1. User**

- `userId`, `name`, `email`

**2. Event**

- `eventId`, `name`, `category` (MOVIE, CONCERT, SPORT)

**3. Show**

- `showId`, `eventId` (points to Event), `venue`, `startTime`

**4. ShowSeat** (the contested resource)

- `showId` (points to Show), `seatNo`, `price`
- `status`: AVAILABLE, HELD or BOOKED
- `bookingId`: who holds or owns it (empty when available)
- `holdExpiresAt`: when a HELD seat becomes free again
- Behaviour: `tryHold()`, `confirm()`, `release()`, `releaseIfExpired()`

**5. Booking**

- `bookingId`, `userId` (points to User), `showId` (points to Show), `seatNos`, `totalAmount`
- `status`: PENDING, CONFIRMED, CANCELLED or EXPIRED
- `holdExpiresAt`, `payment`
- Behaviour: `isExpired()`

**6. Payment**

- `paymentId`, `bookingId` (points to Booking), `amount`
- `status`: SUCCESS, FAILED or REFUNDED
- `idempotencyKey`

### 15.4 Step 4: UML class diagrams (how everything is related)

The class diagram is split into three pictures so each stays readable. Teal boxes are data entities, purple boxes are behaviour classes and interfaces.

**Diagram 1: entities.**

One User makes many Bookings. A Booking is for one Show and has exactly one Payment. An Event has many Shows, and a Show has many ShowSeats. A ShowSeat points back to the Booking that currently holds or owns it (the `bookingId` field). The links are stored ids (`userId`, `showId`, `bookingId`), not object references, exactly like foreign keys in the database.

**Diagram 2: the main service and what it depends on.**

`BookingService` is the class that does the work. It has four collaborators, and it knows **only their interfaces**, never the concrete classes. That is why it can run with in-memory fakes in this section and with a real database and payment provider in production, without changing a line.

**Diagram 3: the implementations behind the interfaces.**

Each interface has an implementation: in-memory repositories (the "database" for learning), a fake payment gateway, and a notification service. To go to production you write `JdbcSeatRepository`, `StripePaymentGateway` and so on, and change only the place where the objects are wired together.

Relationship words in plain terms:

- **implements (is-a):** the class promises to provide the interface's methods (`FakePaymentGateway implements PaymentGateway`).
- **has-a (uses):** the class keeps a reference to another and calls it (`BookingService` has a `PaymentGateway`).
- **creates:** the class builds objects of another type.
- **association with `1` and `*`:** how many of one side relate to the other.

### 15.5 Step 5: Design patterns used, and where and why

**1. Repository: `SeatRepository` and `BookingRepository`.** The service needs to save, find and update seats and bookings, but it should not care whether they live in a `HashMap` or in PostgreSQL. So it talks only to the repository interfaces, and `InMemorySeatRepository` is today's implementation. When you move to a real database you write a new implementation and the service does not change. That is the Repository pattern: hide the storage behind an interface. The important detail is that the *atomic* operations (`tryHold`, `confirm`) are part of the repository interface, because in a real system they are single database statements, and the repository is where that guarantee lives.

**2. Strategy: `PaymentGateway`.** There is more than one way to take a payment (Razorpay, Stripe, a fake for tests), and we may want to switch or add providers. So the service calls `paymentGateway.charge(...)` and does not know who is behind it. Swapping the provider never touches the booking logic. That is the Strategy pattern: one job, many interchangeable ways of doing it.

**3. Observer: `BookingListener`.** When a booking is confirmed or cancelled, several things may want to know: notifications today, analytics or a loyalty system tomorrow. If `confirmBooking` called each of them directly, it would keep growing and become tied to all of them. Instead the service keeps a list of listeners and calls `onConfirmed` on each after a successful booking. `NotificationService` is one listener, and new listeners plug in without editing the booking code. That is the Observer pattern: the publisher announces, the subscribers react. In production this is a Kafka event instead of a method call.

**4. State (as enums with allowed moves): `SeatStatus` and `BookingStatus`.** A seat can only be `AVAILABLE`, `HELD` or `BOOKED`, and a booking can only be `PENDING`, `CONFIRMED`, `CANCELLED` or `EXPIRED`. Each change method checks the current state first (`confirm` only works on a `HELD` seat that belongs to this booking and has not expired). This makes impossible moves, like confirming an expired booking, impossible in code. It is the State idea in its simplest form.

**5. Dependency Injection (a principle, not a GoF pattern).** `BookingService` never creates its own repositories or gateway. They are passed in through the constructor, and `Main` is the only place that decides which concrete classes to use. This is what makes it easy to swap pieces and to test with fakes (for example a gateway that fails on demand).

**Not used on purpose: Singleton.** In real apps the repositories and the service are normally single instances, but we create them once in `Main` and pass them around instead of making them global, which keeps the code easy to test.

### 15.6 Step 6: Code, one piece at a time

All the code below was compiled and run together. Put each class in its own file as in an IDE, or paste them into one file and keep `Main` at the top.

**6.1 Entities and enums.** Mostly data. `ShowSeat` is the interesting one: every method that changes its state is `synchronized`, so "check, then change" is one step. In a real database this is the single conditional `UPDATE` statement from section 8.

```java
import java.util.List;

// ===== Enums: the allowed states (this is the "State" idea) =====
enum SeatStatus { AVAILABLE, HELD, BOOKED }
enum BookingStatus { PENDING, CONFIRMED, CANCELLED, EXPIRED }
enum PaymentStatus { SUCCESS, FAILED, REFUNDED }

// ===== Entity 1: User =====
class User {
    private final String userId;
    private final String name;
    private final String email;

    public User(String userId, String name, String email) {
        this.userId = userId;
        this.name = name;
        this.email = email;
    }
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}

// ===== Entity 2: Event (a movie, a concert, a match) =====
class Event {
    private final String eventId;
    private final String name;
    private final String category;      // MOVIE, CONCERT, SPORT

    public Event(String eventId, String name, String category) {
        this.eventId = eventId;
        this.name = name;
        this.category = category;
    }
    public String getEventId() { return eventId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
}

// ===== Entity 3: Show (one screening / one performance of an event) =====
class Show {
    private final String showId;
    private final String eventId;       // points to Event
    private final String venue;
    private final long startTime;       // epoch millis

    public Show(String showId, String eventId, String venue, long startTime) {
        this.showId = showId;
        this.eventId = eventId;
        this.venue = venue;
        this.startTime = startTime;
    }
    public String getShowId() { return showId; }
    public String getEventId() { return eventId; }
    public String getVenue() { return venue; }
    public long getStartTime() { return startTime; }
}

// ===== Entity 4: ShowSeat (one seat in ONE show: this is the thing people fight for) =====
class ShowSeat {
    private final String showId;
    private final String seatNo;        // "A1"
    private final double price;
    private SeatStatus status = SeatStatus.AVAILABLE;
    private String bookingId;           // who holds or owns it (null when available)
    private long holdExpiresAt;         // epoch millis, only meaningful while HELD

    public ShowSeat(String showId, String seatNo, double price) {
        this.showId = showId;
        this.seatNo = seatNo;
        this.price = price;
    }

    // Every method that changes state is synchronized, so "check + change" happens as ONE step.
    // In a real database this is the single statement:
    //   UPDATE show_seat SET status='HELD', ... WHERE seat=? AND (status='AVAILABLE' OR hold expired)

    public synchronized boolean tryHold(String bookingId, long now, long expiresAt) {
        boolean free = status == SeatStatus.AVAILABLE
                    || (status == SeatStatus.HELD && now > holdExpiresAt);   // old hold ran out
        if (!free) return false;
        this.status = SeatStatus.HELD;
        this.bookingId = bookingId;
        this.holdExpiresAt = expiresAt;
        return true;
    }

    public synchronized boolean confirm(String bookingId, long now) {
        boolean mine = status == SeatStatus.HELD
                    && bookingId.equals(this.bookingId)
                    && now <= holdExpiresAt;                                  // hold still valid
        if (!mine) return false;
        this.status = SeatStatus.BOOKED;
        return true;
    }

    // Give the seat back, but only if it still belongs to this booking
    public synchronized boolean release(String bookingId) {
        if (status != SeatStatus.AVAILABLE && bookingId.equals(this.bookingId)) {
            this.status = SeatStatus.AVAILABLE;
            this.bookingId = null;
            return true;
        }
        return false;
    }

    // Used by the background cleanup job
    public synchronized boolean releaseIfExpired(long now) {
        if (status == SeatStatus.HELD && now > holdExpiresAt) {
            this.status = SeatStatus.AVAILABLE;
            this.bookingId = null;
            return true;
        }
        return false;
    }

    public String getShowId() { return showId; }
    public String getSeatNo() { return seatNo; }
    public double getPrice() { return price; }
    public synchronized SeatStatus getStatus() { return status; }
    public synchronized String getBookingId() { return bookingId; }
}

// ===== Entity 5: Payment =====
class Payment {
    private final String paymentId;
    private final String bookingId;     // points to Booking
    private final double amount;
    private final PaymentStatus status;
    private final String idempotencyKey;

    public Payment(String paymentId, String bookingId, double amount,
                   PaymentStatus status, String idempotencyKey) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
    }
    public String getPaymentId() { return paymentId; }
    public String getBookingId() { return bookingId; }
    public double getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
}

// ===== Entity 6: Booking (the user's order: several seats + one payment) =====
class Booking {
    private final String bookingId;
    private final String userId;        // points to User
    private final String showId;        // points to Show
    private final List<String> seatNos;
    private final double totalAmount;
    private final long createdAt;
    private final long holdExpiresAt;
    private volatile BookingStatus status = BookingStatus.PENDING;
    private volatile Payment payment;

    public Booking(String bookingId, String userId, String showId, List<String> seatNos,
                   double totalAmount, long createdAt, long holdExpiresAt) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.showId = showId;
        this.seatNos = seatNos;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.holdExpiresAt = holdExpiresAt;
    }

    public boolean isExpired(long now) { return now > holdExpiresAt; }

    public String getBookingId() { return bookingId; }
    public String getUserId() { return userId; }
    public String getShowId() { return showId; }
    public List<String> getSeatNos() { return seatNos; }
    public double getTotalAmount() { return totalAmount; }
    public long getHoldExpiresAt() { return holdExpiresAt; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }
}
```

**Why does `Booking` keep the list of seats (`seatNos`) and not the `Show`?** (Reasoning only, the code does not change.) The list answers the question "which seats did *this order* choose?", and that is a fact about the order, not about the show.

- **After payment we need exactly these seats.** To confirm, release on failure or expiry, cancel, refund, or print the ticket, we must find the seats of this one booking. With the list in `Booking` we go straight to them (two or three lookups). If the list lived in `Show`, we would first have to search the whole show's seats for the ones with this booking id, and a show can have 200 to 50,000 seats.
- **`Show` would become one big shared object that everyone changes.** If every booking appended its seats to a list inside the show, thousands of users would all be writing to the same object at the same time, so we would need to lock the whole show to be safe. Keeping the state on the individual `ShowSeat` objects means each seat is protected on its own, which is why two users booking different seats never block each other. A `Show` is a stable description (event, venue, start time) and should stay that way.
- **A booking is a record of an order.** It stays correct for history, refunds and support even after the show is over, and it belongs to one user. The user's "my bookings" page reads bookings, not shows.
- **It matches the database design.** In the tables, the link goes from the seat to the booking (`show_seats.booking_id`), which is the usual many-to-one direction (many seats point to one booking). The authority on a seat's state is the `ShowSeat` row. The `seatNos` list in the Java `Booking` is a convenience copy of "the seats whose booking id is this booking", so we do not have to search for them. In a real database this convenience list is often a small `booking_items` table (booking id, seat number, price paid).

The trade-off is that the same fact is stored in two places (`ShowSeat.bookingId` and `Booking.seatNos`), so the code must update both together. That is why `holdSeats`, `confirmBooking` and `cancelBooking` in the service always change them in the same method, and the seat row stays the single source of truth for who owns a seat.

**6.2 Exceptions.** One small class per error so the service can say clearly what went wrong.

```java
class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException(String seatNo) { super("Seat not available: " + seatNo); }
}
class HoldExpiredException extends RuntimeException {
    public HoldExpiredException(String bookingId) { super("Hold expired for booking: " + bookingId); }
}
class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String bookingId) { super("Payment failed for booking: " + bookingId); }
}
class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String bookingId) { super("No such booking: " + bookingId); }
}
```

**6.3 Repositories.** The interface plus in-memory implementations. `tryHold` and `confirm` delegate to the seat's own atomic methods.

**Do you need the repository layer in an interview?** Not always. It depends on the kind of round and how much time you have.

- **Machine coding / LLD round (45 to 90 minutes, running code).** You can show **entities and the service interacting directly**, with the data kept in `ConcurrentHashMap` fields inside the service. Most interviewers care about: the right entities, clean service methods, the seat-hold logic being correct and thread safe, and one or two sensible patterns. A repository interface is a nice extra, but writing four extra files can cost you time you need for the hard part.
- **Design-patterns or senior-level round.** Here the repository interface earns its place, because it shows you understand separation of concerns and can swap storage without touching business logic.
- **System design (HLD) round, no code.** You do not draw repositories at all. You draw "Booking DB" and explain the atomic conditional update.

**A safe approach:** start with the simple version, and say one sentence out loud: *"I am keeping the data in maps inside the service to save time. In production I would put a repository interface in front so I can swap in a database, and the atomic hold would be one conditional UPDATE."* If the interviewer asks to see it, extract the interface then, which takes a couple of minutes because the seat's atomic methods already live in `ShowSeat`.

The simple version looks like this (the same logic, without the repository classes):

```
class BookingService {
    private final Map<String, Map<String, ShowSeat>> seats = new ConcurrentHashMap<>(); // showId -> seatNo -> seat
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final PaymentGateway paymentGateway;

    Booking holdSeats(String userId, String showId, List<String> seatNos) {
        ...
        ShowSeat seat = seats.get(showId).get(seatNo);
        if (!seat.tryHold(bookingId, now, expiresAt)) { /* undo, then throw */ }
        ...
    }
}
```

**What you must never skip, with or without a repository:** the atomic check-and-change on the seat (so two users cannot take the same seat), the all-or-nothing hold, the idempotent payment, and being able to explain *where* that atomicity lives (in the seat now, in a conditional `UPDATE` in a real database). That is what interviewers actually test; the repository is only packaging.

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ===== Repository for seats: the service sees only this interface =====
interface SeatRepository {
    void addSeat(ShowSeat seat);
    ShowSeat find(String showId, String seatNo);
    List<ShowSeat> findByShow(String showId);
    boolean tryHold(String showId, String seatNo, String bookingId, long now, long expiresAt);
    boolean confirm(String showId, String seatNo, String bookingId, long now);
    void release(String showId, String seatNo, String bookingId);
    int releaseExpired(long now);                  // the cleanup job calls this
}

class InMemorySeatRepository implements SeatRepository {
    // showId -> (seatNo -> seat)
    private final Map<String, Map<String, ShowSeat>> table = new ConcurrentHashMap<>();

    @Override
    public void addSeat(ShowSeat seat) {
        table.computeIfAbsent(seat.getShowId(), k -> new ConcurrentHashMap<>())
             .put(seat.getSeatNo(), seat);
    }

    @Override
    public ShowSeat find(String showId, String seatNo) {
        Map<String, ShowSeat> seats = table.get(showId);
        ShowSeat seat = seats == null ? null : seats.get(seatNo);
        if (seat == null) throw new IllegalArgumentException("No such seat: " + seatNo);
        return seat;
    }

    @Override
    public List<ShowSeat> findByShow(String showId) {
        return new ArrayList<>(table.getOrDefault(showId, Map.of()).values());
    }

    @Override
    public boolean tryHold(String showId, String seatNo, String bookingId, long now, long expiresAt) {
        return find(showId, seatNo).tryHold(bookingId, now, expiresAt);   // atomic inside the seat
    }

    @Override
    public boolean confirm(String showId, String seatNo, String bookingId, long now) {
        return find(showId, seatNo).confirm(bookingId, now);
    }

    @Override
    public void release(String showId, String seatNo, String bookingId) {
        find(showId, seatNo).release(bookingId);
    }

    @Override
    public int releaseExpired(long now) {
        int count = 0;
        for (Map<String, ShowSeat> seats : table.values()) {
            for (ShowSeat seat : seats.values()) {
                if (seat.releaseIfExpired(now)) count++;
            }
        }
        return count;
    }
}

// ===== Repository for bookings =====
interface BookingRepository {
    void save(Booking booking);
    Booking findById(String bookingId);            // throws if missing
}

class InMemoryBookingRepository implements BookingRepository {
    private final Map<String, Booking> table = new ConcurrentHashMap<>();

    @Override
    public void save(Booking booking) { table.put(booking.getBookingId(), booking); }

    @Override
    public Booking findById(String bookingId) {
        Booking booking = table.get(bookingId);
        if (booking == null) throw new BookingNotFoundException(bookingId);
        return booking;
    }
}
```

**6.4 Payment (Strategy) and notifications (Observer).** The fake gateway uses `computeIfAbsent` on the idempotency key, so the same key never charges twice.

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// ===== Strategy: any payment provider can be plugged in =====
interface PaymentGateway {
    Payment charge(String bookingId, String idempotencyKey, double amount);
    void refund(String idempotencyKey);
}

// A fake provider for learning and tests
class FakePaymentGateway implements PaymentGateway {
    private final Map<String, Payment> chargedByKey = new ConcurrentHashMap<>();
    private final AtomicInteger realCharges = new AtomicInteger();
    private volatile boolean shouldFail = false;

    public void setShouldFail(boolean shouldFail) { this.shouldFail = shouldFail; }
    public int getRealCharges() { return realCharges.get(); }

    @Override
    public Payment charge(String bookingId, String idempotencyKey, double amount) {
        // Same key again -> return the earlier result, never charge twice
        return chargedByKey.computeIfAbsent(idempotencyKey, key -> {
            if (shouldFail) {
                return new Payment(UUID.randomUUID().toString(), bookingId, amount,
                                   PaymentStatus.FAILED, key);
            }
            realCharges.incrementAndGet();
            return new Payment(UUID.randomUUID().toString(), bookingId, amount,
                               PaymentStatus.SUCCESS, key);
        });
    }

    @Override
    public void refund(String idempotencyKey) {
        System.out.println("   [payment] refund issued for key " + idempotencyKey);
    }
}

// ===== Observer: anyone who wants to hear about booking changes =====
interface BookingListener {
    void onConfirmed(Booking booking);
    void onCancelled(Booking booking);
}

class NotificationService implements BookingListener {
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onConfirmed(Booking booking) {
        sent.add("CONFIRMED " + booking.getBookingId());
        System.out.println("   [notify] booking confirmed, seats " + booking.getSeatNos());
    }

    @Override
    public void onCancelled(Booking booking) {
        sent.add("CANCELLED " + booking.getBookingId());
        System.out.println("   [notify] booking cancelled");
    }

    public int getSentCount() { return sent.size(); }
}
```

**6.5 The main service.** Read `holdSeats` and `confirmBooking` slowly; they are the heart of the system.

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class BookingService {
    private final SeatRepository seatRepository;          // Repository
    private final BookingRepository bookingRepository;    // Repository
    private final PaymentGateway paymentGateway;          // Strategy
    private final long holdMillis;                        // how long a hold lasts
    private final List<BookingListener> listeners = new ArrayList<>();   // Observers
    // idempotency key -> bookingId it already confirmed
    private final Map<String, String> processedKeys = new ConcurrentHashMap<>();

    // Dependency Injection: everything comes in through the constructor
    public BookingService(SeatRepository seatRepository, BookingRepository bookingRepository,
                          PaymentGateway paymentGateway, long holdMillis) {
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.paymentGateway = paymentGateway;
        this.holdMillis = holdMillis;
    }

    public void addListener(BookingListener listener) { listeners.add(listener); }

    // ---------- STEP 1: hold the seats (all of them, or none) ----------
    public Booking holdSeats(String userId, String showId, List<String> seatNos) {
        if (seatNos == null || seatNos.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one seat");
        }
        List<String> sorted = new ArrayList<>(seatNos);
        Collections.sort(sorted);                          // always the same order (avoids deadlocks in a real DB)

        String bookingId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expiresAt = now + holdMillis;

        List<String> held = new ArrayList<>();
        double total = 0;
        for (String seatNo : sorted) {
            boolean ok = seatRepository.tryHold(showId, seatNo, bookingId, now, expiresAt);
            if (!ok) {
                for (String done : held) {                 // undo the seats we already got
                    seatRepository.release(showId, done, bookingId);
                }
                throw new SeatUnavailableException(seatNo);
            }
            held.add(seatNo);
            total += seatRepository.find(showId, seatNo).getPrice();
        }

        Booking booking = new Booking(bookingId, userId, showId, sorted, total, now, expiresAt);
        bookingRepository.save(booking);
        return booking;
    }

    // ---------- STEP 2: pay and confirm ----------
    public Booking confirmBooking(String bookingId, String idempotencyKey) {
        // Same key already processed? Return the earlier result, do nothing again.
        String alreadyDone = processedKeys.get(idempotencyKey);
        if (alreadyDone != null) return bookingRepository.findById(alreadyDone);

        Booking booking = bookingRepository.findById(bookingId);
        synchronized (booking) {                           // one confirm at a time per booking
            if (booking.getStatus() == BookingStatus.CONFIRMED) return booking;
            if (booking.getStatus() != BookingStatus.PENDING) {
                throw new IllegalStateException("Booking is " + booking.getStatus());
            }

            long now = System.currentTimeMillis();
            if (booking.isExpired(now)) {                  // the 10 minutes ran out
                releaseSeats(booking);
                booking.setStatus(BookingStatus.EXPIRED);
                throw new HoldExpiredException(bookingId);
            }

            Payment payment = paymentGateway.charge(bookingId, idempotencyKey, booking.getTotalAmount());
            if (payment.getStatus() != PaymentStatus.SUCCESS) {
                releaseSeats(booking);                     // free the seats for others
                booking.setStatus(BookingStatus.CANCELLED);
                throw new PaymentFailedException(bookingId);
            }

            // Payment worked. Turn every HELD seat into BOOKED.
            for (String seatNo : booking.getSeatNos()) {
                boolean ok = seatRepository.confirm(booking.getShowId(), seatNo, bookingId,
                                                    System.currentTimeMillis());
                if (!ok) {                                 // hold was lost while we were paying
                    paymentGateway.refund(idempotencyKey); // give the money back
                    releaseSeats(booking);
                    booking.setStatus(BookingStatus.EXPIRED);
                    throw new HoldExpiredException(bookingId);
                }
            }

            booking.setPayment(payment);
            booking.setStatus(BookingStatus.CONFIRMED);
            processedKeys.put(idempotencyKey, bookingId);
            for (BookingListener listener : listeners) listener.onConfirmed(booking);
            return booking;
        }
    }

    // ---------- Cancel (pending or confirmed) ----------
    public void cancelBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId);
        synchronized (booking) {
            if (booking.getStatus() == BookingStatus.CANCELLED
                    || booking.getStatus() == BookingStatus.EXPIRED) return;   // already cancelled
            boolean wasPaid = booking.getStatus() == BookingStatus.CONFIRMED;
            releaseSeats(booking);
            booking.setStatus(BookingStatus.CANCELLED);
            if (wasPaid) paymentGateway.refund(booking.getPayment().getIdempotencyKey());
            for (BookingListener listener : listeners) listener.onCancelled(booking);
        }
    }

    // ---------- Background job: free holds that ran out ----------
    public int releaseExpiredHolds() {
        return seatRepository.releaseExpired(System.currentTimeMillis());
    }

    private void releaseSeats(Booking booking) {
        for (String seatNo : booking.getSeatNos()) {
            seatRepository.release(booking.getShowId(), seatNo, booking.getBookingId());
        }
    }
}
```

**6.6 Main.** Wires everything together and tests every feature, including the concurrency cases.

```java
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    // A deliberately BROKEN seat: check and change are two separate steps
    static class BrokenSeat {
        private SeatStatus status = SeatStatus.AVAILABLE;
        boolean tryHold() {
            if (status == SeatStatus.AVAILABLE) {   // 1. check
                Thread.yield();                     //    (another thread may run right here)
                status = SeatStatus.HELD;           // 2. change
                return true;
            }
            return false;
        }
    }

    static SeatRepository newSeats(String showId, String... seatNos) {
        SeatRepository repo = new InMemorySeatRepository();
        for (String s : seatNos) repo.addSeat(new ShowSeat(showId, s, 250.0));
        return repo;
    }

    static void expect(String label, Runnable action) {
        try { action.run(); System.out.println(label + " -> no error (unexpected)"); }
        catch (RuntimeException e) { System.out.println(label + " -> " + e.getMessage()); }
    }

    public static void main(String[] args) throws Exception {
        SeatRepository seats = newSeats("show1", "A1", "A2", "A3", "A4", "B1");
        FakePaymentGateway gateway = new FakePaymentGateway();
        BookingService service = new BookingService(seats, new InMemoryBookingRepository(), gateway, 300);
        NotificationService notifications = new NotificationService();
        service.addListener(notifications);

        // 1. hold two seats
        Booking b1 = service.holdSeats("user1", "show1", List.of("A2", "A1"));
        System.out.println("1. Hold A1,A2     : " + b1.getStatus() + ", total " + b1.getTotalAmount());

        // 2. someone else wants A1
        expect("2. Other user, A1  ", () -> service.holdSeats("user2", "show1", List.of("A1")));

        // 3. pay and confirm
        Booking done = service.confirmBooking(b1.getBookingId(), "pay-key-1");
        System.out.println("3. After payment  : " + done.getStatus() + ", A1 is "
                + seats.find("show1", "A1").getStatus());

        // 4. same request again (client retry): charged only once
        service.confirmBooking(b1.getBookingId(), "pay-key-1");
        System.out.println("4. Retry same key : real charges = " + gateway.getRealCharges() + " (expected 1)");

        // 5. all or nothing: A3 is free but A1 is booked
        expect("5. Hold A3 + A1    ", () -> service.holdSeats("user3", "show1", List.of("A3", "A1")));
        System.out.println("   A3 afterwards   : " + seats.find("show1", "A3").getStatus() + " (expected AVAILABLE)");

        // 6. a hold that runs out
        Booking b2 = service.holdSeats("user4", "show1", List.of("A4"));
        Thread.sleep(400);                                     // hold lasts 300 ms
        Booking b3 = service.holdSeats("user5", "show1", List.of("A4"));
        System.out.println("6. After expiry   : user5 got A4, status " + b3.getStatus());
        expect("   user4 pays late  ", () -> service.confirmBooking(b2.getBookingId(), "pay-key-2"));

        // 7. payment failure frees the seat
        gateway.setShouldFail(true);
        Booking b4 = service.holdSeats("user6", "show1", List.of("B1"));
        expect("7. Payment fails    ", () -> service.confirmBooking(b4.getBookingId(), "pay-key-3"));
        System.out.println("   B1 afterwards   : " + seats.find("show1", "B1").getStatus() + " (expected AVAILABLE)");
        gateway.setShouldFail(false);

        // 8. cancel a confirmed booking
        service.cancelBooking(b1.getBookingId());
        System.out.println("8. After cancel   : A1 is " + seats.find("show1", "A1").getStatus());

        // 9. background cleanup
        service.holdSeats("user7", "show1", List.of("A3"));
        Thread.sleep(400);
        System.out.println("9. Cleanup job    : released " + service.releaseExpiredHolds() + " expired hold(s)");

        // ---- Concurrency: 100 users fight for the SAME seat ----
        // 10. broken version: check and change are separate
        BrokenSeat broken = new BrokenSeat();
        AtomicInteger brokenWinners = new AtomicInteger();
        race(100, () -> { if (broken.tryHold()) brokenWinners.incrementAndGet(); });
        System.out.println("10. Broken seat   : " + brokenWinners.get() + " users got the same seat (double booking!)");

        // 11. correct version, 5 "server nodes" (5 services) sharing ONE seat store (the "database")
        SeatRepository sharedDb = newSeats("show2", "Z1");
        BookingService[] nodes = new BookingService[5];
        for (int i = 0; i < 5; i++) {
            nodes[i] = new BookingService(sharedDb, new InMemoryBookingRepository(),
                                          new FakePaymentGateway(), 60_000);
        }
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        final int[] counter = {0};
        race(100, () -> {
            int n;
            synchronized (counter) { n = counter[0]++; }
            try {
                nodes[n % 5].holdSeats("user" + n, "show2", List.of("Z1"));
                winners.incrementAndGet();
            } catch (SeatUnavailableException e) {
                losers.incrementAndGet();
            }
        });
        System.out.println("11. 5 nodes, 100 users, 1 seat : winners = " + winners.get()
                + ", rejected = " + losers.get());
    }

    // starts all threads at the same moment so the requests really overlap
    static void race(int threads, Runnable work) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                work.run();
            });
            ts[i].start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
    }
}
```

Output when run (booking ids and the count in test 10 differ from run to run):

```
1. Hold A1,A2     : PENDING, total 500.0
2. Other user, A1   -> Seat not available: A1
   [notify] booking confirmed, seats [A1, A2]
3. After payment  : CONFIRMED, A1 is BOOKED
4. Retry same key : real charges = 1 (expected 1)
5. Hold A3 + A1     -> Seat not available: A1
   A3 afterwards   : AVAILABLE (expected AVAILABLE)
6. After expiry   : user5 got A4, status PENDING
   user4 pays late   -> Hold expired for booking: de8f0458-8716-4ea3-ada6-1bb2da2f2226
7. Payment fails     -> Payment failed for booking: fbe948a8-e493-442e-a4c5-1c7fe20a3b6d
   B1 afterwards   : AVAILABLE (expected AVAILABLE)
   [payment] refund issued for key pay-key-1
   [notify] booking cancelled
8. After cancel   : A1 is AVAILABLE
9. Cleanup job    : released 2 expired hold(s)
10. Broken seat   : 5 users got the same seat (double booking!)
11. 5 nodes, 100 users, 1 seat : winners = 1, rejected = 99
```

How to read the results:

- Tests 1 to 3: the happy path. Two seats held, a second user rejected, payment makes them `BOOKED`, and the observer prints the notification.
- Test 4: the same idempotency key was sent twice and the card was charged once.
- Test 5: asking for `A3` (free) and `A1` (booked) fails, and `A3` stays `AVAILABLE`. That is **all or nothing**.
- Test 6: user 5 could take `A4` after user 4's hold ran out, and user 4's late payment was refused.
- Test 7: payment failure frees the seat. Test 8: cancelling a confirmed booking frees the seat and refunds.
- Test 9: the background cleanup released the holds that had expired.
- Test 10 is the **bug on purpose**: with check and change as two steps, more than one user gets the same seat.
- Test 11 is the fix at scale: 5 service instances (simulated servers) and 100 users, one seat, exactly one winner.

### 15.7 Step 7: How the main services work (flow of each feature)

**Hold flow** (`holdSeats(userId, showId, seatNos)`):

```
1. sort the seat numbers                    → same order every time (avoids deadlocks in a real DB)
2. for each seat: seatRepository.tryHold()  → atomic "hold only if free"
     success → remember it, add its price
     failure → release every seat we already got, throw SeatUnavailableException
3. create Booking (PENDING) with the expiry time and save it
```

**Confirm flow** (`confirmBooking(bookingId, idempotencyKey)`):

```
1. key already processed?         → return the earlier result (no second charge)
2. booking not PENDING?           → already confirmed: return it, otherwise error
3. hold expired?                  → release seats, booking EXPIRED, HoldExpiredException
4. paymentGateway.charge(key)     → FAILED: release seats, booking CANCELLED, exception
5. for each seat: confirm()       → any false: refund, release, EXPIRED, exception
6. booking CONFIRMED, remember key, tell every listener
```

**Cancel flow:** release the seats, mark the booking `CANCELLED`, refund if it had been paid, and notify listeners. **Cleanup flow:** `releaseExpiredHolds()` walks the seats and frees every `HELD` seat whose time has passed; in production this is the background worker.

Where the thread safety comes from: the **atomic seat methods** (no two users can take the same seat), `synchronized (booking)` (one confirm at a time per booking), `computeIfAbsent` in the gateway (one charge per key) and the repositories' concurrent maps.

### 15.8 What to say in the interview about the LLD

> "I found the entities from the requirements: User, Event, Show, ShowSeat, Booking and Payment. The main class is BookingService, and it depends only on interfaces: a seat repository, a booking repository, a payment gateway and booking listeners. The repository is where the atomic hold and confirm live, because in production those are single conditional updates. Payment is a Strategy so I can switch providers, notifications are an Observer so I can add consumers without touching booking code, and seat and booking states are explicit enums so impossible moves are rejected. Holds are all or nothing, payments are idempotent, and I tested it with 100 concurrent users on one seat across five simulated servers: exactly one wins."

## 16. Concurrency Practice

### Exercise 1: Make the double booking happen, then fix it

**What it simulates.** 100 threads in one JVM, which models **one server receiving 100 requests at the same instant** (a web server handles each request on its own thread). All of them want the same seat.

**The broken version** (`BrokenSeat` in `Main`). It checks the status and then changes it as two separate steps. Between the two steps another thread can get in. To make this visible, the code calls `Thread.yield()` between the check and the change, which politely lets other threads run right there. (Without the yield the bug is rare and hard to reproduce, which is exactly why these bugs reach production.) In test 10, more than one thread got the seat: **double booking**.

**The fix.** `ShowSeat.tryHold` is `synchronized`, so only one thread at a time can run the check-and-change. The second thread waits a few nanoseconds, then sees `HELD` and fails. This is the in-memory version of the database's atomic conditional `UPDATE`.

### Exercise 2: Several servers

**What changes with multiple server nodes.** `synchronized` only works **inside one JVM**. Server 1 and server 2 are different processes on different machines with no shared memory, so a Java lock on server 1 cannot stop server 2. If each server kept its own copy of the seat status, both could sell the same seat.

**The rule:** the atomic check-and-change must live in the **one place all servers share**: the database (a conditional `UPDATE`) or a single Redis key (`SET NX`). In our simulation this is the `SeatRepository`: five `BookingService` objects (the five servers) share one `SeatRepository` (the database), and the atomic method inside it decides the winner. Test 11 sends 100 users at five servers for one seat: **1 winner and 99 rejected**. Notice that the services have no locks for this at all. The correctness lives in the shared store.

**What if a server crashes after holding the seat?** The hold has an expiry, so the seat comes back by itself. We never need to "clean up after a crashed server".

### Exercise 3: Retried payments

Call `confirmBooking` twice with the same idempotency key (test 4). The gateway's `computeIfAbsent` runs the charge at most once per key, and the service returns the earlier result. In production this is the `UNIQUE` constraint on `payments.idempotency_key`. Try changing the code to use a new key on the retry and see the second charge happen, then restore it.

### Exercise 4 (for you to try)

Make `FakePaymentGateway.charge` sleep for 500 ms and lower the hold to 300 ms. The user pays, the charge succeeds, but the hold has expired by the time the seats are confirmed. Watch the refund path run. Then add the "extend hold when payment starts" rule from section 8.6 and see the failure disappear.

## 17. Failure Scenarios

### Case 1: Two users click the same seat at the same moment

The atomic conditional update lets exactly one win. The other gets `409` and the seat turns grey on their screen. This is the whole point of section 8.

### Case 2: A user holds seats and disappears

The hold expires after 10 minutes. The conditional update treats an expired hold as free, so the next buyer can take it immediately, even before the cleanup job runs.

### Case 3: Payment succeeds but the hold already expired

The confirm step re-checks the hold and finds it lost. We refund automatically (or, if the seat is still free, re-hold it and finish the booking). Extending the hold when payment starts makes this rare.

### Case 4: The payment request times out (we do not know if the user was charged)

Do not guess. Mark the booking "payment pending", keep the hold, and wait for the gateway's **webhook** or query the gateway with the idempotency key. The reconciliation job catches anything that falls through.

### Case 5: Webhook arrives twice, or after the user retried

The handler is idempotent: it looks up the payment by the gateway's payment id and does nothing if it is already processed.

### Case 6: Redis (hold filter, cache) is down

Fall back to the database. Redis is an optimization, not the source of truth. Protect the database with the waiting room and rate limits, because it will see more traffic.

### Case 7: The primary database of a shard fails

Promote a replica (automatic failover). Bookings on that shard are briefly unavailable, which is acceptable because we prefer an error to a wrong answer (consistency over availability for writes). Browsing continues from caches and other shards.

### Case 8: A booking server crashes in the middle of a hold

If the transaction did not commit, nothing was held. If it committed, the hold simply expires. There is nothing to repair.

### Case 9: Kafka or the notification service is down

Booking is unaffected. Events wait in the outbox table and are published when Kafka is back. Users get their ticket email a bit late, and the ticket is still visible in the app.

### Case 10: A flash sale overloads the system

The waiting room admits only as many users as the booking shard can handle, the seat map is served from the CDN and cache, and clients get push updates instead of polling.

### Case 11: Bots buy all the good seats

Rate limiting, CAPTCHA, per-account seat limits, device and phone verification, and the randomized waiting room.

### Case 12: Clocks disagree between servers

Hold expiry always uses the database's own clock (`now()` inside the SQL), not the servers' clocks, so every server agrees on whether a hold is still valid.

### Case 13: Region outage

Run the booking database as a multi-region setup where each show has one "home" region that owns its seats (all writes for that show go there), with replicas elsewhere for reads. If the home region fails, promote another and accept a short pause for that region's shows.

## 18. Advanced (Interview Bonus) Topics

### 18.1 One writer per show (actor model / partitioned queue)

Instead of locks and conditional updates racing, send all hold requests for a show to a single queue partition and process them one by one on one consumer. There is no concurrency inside a show, so no race by construction. It is very simple to reason about and can give fair ordering. The cost is that one show's throughput is limited to one consumer, and a failed consumer needs a quick takeover.

### 18.2 Fencing tokens

If you use a distributed lock with an expiry, a slow process may wake up after its lock expired and write anyway. A **fencing token** is a number that increases with each lock grant, and the storage rejects writes carrying an older token than it has seen. In our design the conditional update on the seat row (which checks the current `booking_id`) plays this role.

### 18.3 Outbox pattern

To publish an event reliably, write the event to an `outbox` table in the same transaction as the booking, and let a separate process publish it to Kafka and mark it sent. This avoids the "booking saved but event lost" and "event sent but booking rolled back" problems.

### 18.4 Seat adjacency and best-available

"Give me 4 seats together" is a search problem: find a run of adjacent free seats in a row. Keep each row's free seats in a compact form (bitmap) so a run can be found quickly, then hold the chosen seats with the same atomic update. If the update fails because someone else took one, pick the next run.

### 18.5 Overbooking

Airlines deliberately sell more tickets than seats because some passengers do not show up. It trades correctness for revenue and needs compensation rules. For events with assigned seats we do not do this.

### 18.6 Dynamic pricing

Price changes with demand. Store the price on the `ShowSeat` row at hold time, so the price the user saw is the price they pay.

### 18.7 General admission (no seat numbers)

Inventory is a counter per ticket type. The atomic step is `UPDATE tickets SET remaining = remaining - :n WHERE show_id = :s AND remaining >= :n` and the same condition-in-the-update idea applies.

## 19. Interview Follow-Up Questions

### Requirements

1. What are the functional requirements?
2. What are the non-functional requirements?
3. What must be strongly consistent and what can be stale?
4. How long should a hold last and why?
5. What happens if payment succeeds after the hold expired?

### Estimation

6. What are the read and booking QPS?
7. What is the difference between average and peak QPS, and which do you design for?
8. How much storage do you need?
9. How many holds can be active at once?
10. How do you size the system for one very popular event?

### Preventing double booking

11. How do you make sure two users never get the same seat?
12. What is a race condition? Show one here.
13. What is the difference between pessimistic and optimistic locking?
14. Why not hold a database lock while the user pays?
15. What is an atomic conditional update?
16. How do you hold several seats all or nothing?
17. What is a deadlock and how do you avoid it?
18. Is a Redis lock enough? Why or why not?
19. What is a fencing token?
20. How do you handle expired holds?
21. Why use the database clock for expiry?

### Database

22. SQL or NoSQL and why?
23. What is ACID?
24. What are the primary keys and why?
25. What indexes do you need?
26. How do you shard and why by `show_id`?
27. What is consistent hashing?
28. How do you handle a hot shard?
29. What about replication lag and read-your-writes?

### Flows and payments

30. Walk through hold, pay and confirm.
31. What is idempotency and where do you need it?
32. How do you avoid charging a customer twice?
33. What is a webhook and how do you handle duplicates?
34. What is a saga and where do you use one?
35. What if the payment request times out?
36. How do you do refunds and cancellations?

### Caching and spikes

37. What can and cannot be cached?
38. What is a local in-process cache and when does it help?
39. What is a cache stampede?
40. How do you avoid 200,000 people polling the seat map?
41. What is a waiting room and how would you build it?
42. How do you keep the queue fair?

### Reliability

43. What happens if Redis is down?
44. What happens if the primary database fails?
45. What if a server crashes in the middle of a booking?
46. What is the outbox pattern?
47. How do you handle a region failure?

### Security and abuse

48. How do you stop bots and scalpers?
49. How do you keep card data safe?
50. How do you make QR tickets hard to forge or reuse?

### LLD

51. Which design patterns did you use and why?
52. Why is the atomic operation part of the repository interface?
53. How would you test the concurrency behaviour?

## 20. Extra Topics: Study List

### Must know

```
⭐ Race conditions and atomic operations
⭐ Transactions and ACID
⭐ Pessimistic vs optimistic locking
⭐ Deadlocks and lock ordering
⭐ Idempotency and idempotency keys
⭐ Caching (cache-aside, TTL, stampede, hot key)
⭐ Replication and replication lag
⭐ Sharding and choosing a shard key
⭐ Webhooks and retries
⭐ Rate limiting (token bucket)
⭐ HTTP status codes (409, 410, 402, 429)
```

### Advanced

```
⭐ Consistent hashing
⭐ Saga pattern and compensating actions
⭐ Outbox pattern
⭐ Redis (SET NX EX, sorted sets) and its failure modes
⭐ Kafka and at-least-once delivery
⭐ Fencing tokens and distributed locks
⭐ Waiting room / virtual queue design
⭐ Single writer per partition (actor model)
⭐ Multi-region design and failover
⭐ WebSockets and Server-Sent Events
```

## 21. Java Practice Roadmap

```
Phase 1: Pure Java
  Entities, enums, exceptions, in-memory repositories

Phase 2: LLD
  Repository, Strategy (payment), Observer (notifications), state enums, DI

Phase 3: Multithreading
  synchronized, ConcurrentHashMap.computeIfAbsent, the 100-thread race test,
  CountDownLatch to start threads together

Phase 4: Spring Boot
  REST APIs, JPA/Hibernate, PostgreSQL, conditional UPDATE and @Transactional

Phase 5: Redis
  SET NX EX holds, seat-map cache, waiting room with a sorted set

Phase 6: Kafka
  BookingConfirmed events, outbox pattern, notification consumer

Phase 7: Reliability
  Idempotency keys end to end, payment reconciliation job, hold-expiry worker

Phase 8: Scaling
  Docker, three instances behind Nginx, k6 or JMeter load test with
  1,000 users for one seat: assert exactly one booking

Phase 9: Advanced
  Sharding by show id, multi-region, waiting-room load test
```

## 22. Final 30-Second Interview Explanation

> "A ticket booking system has a small, fixed inventory that many people compete for, and its traffic is very spiky, so the core requirement is that a seat is never sold twice. I split it into a catalog and search side that is cached and eventually consistent, and a booking side that is strongly consistent. When a user picks seats I do an atomic conditional update in the database that sets them to HELD with an expiry, only if they are free or their old hold has expired, all or nothing in one transaction on the shard that owns the show. No lock is kept while the user pays; the hold is a lease that expires by itself. Payment goes through a gateway with an idempotency key, and a second conditional update then turns HELD into BOOKED only if the hold is still valid, otherwise I refund. The database is sharded by show id so a multi-seat hold stays on one shard, and for flash sales I add a Redis hold filter, push updates, and a waiting room to control how many people reach the booking step. Notifications go through Kafka with an outbox so booking never waits on them."

### ⭐ The 5 things you absolutely must understand

```
1. WHY a race happens?        → check and change as two steps lets two users win
2. WHY atomic conditional     → the database decides in ONE statement, no gap, no lock held while paying
   update?
3. WHY a hold with expiry?    → the user needs minutes to pay; a lease frees itself, crashes need no cleanup
4. WHY idempotency keys?      → retries and webhooks must never charge twice
5. WHY shard by show_id?      → all seats of a show on one shard, so multi-seat holds are single-shard transactions
```

**Most important:** the database is the final judge of who owns a seat, caches and Redis only make things faster, and every step that can be retried is made idempotent. That combination is what makes the design correct under contention and still fast under spikes.