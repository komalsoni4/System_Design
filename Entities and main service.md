# 🔗 URL Shortener (Bitly / TinyURL)

Complete System Design Notes: Requirements → Estimation → Entities → APIs → DB → Key Generation → HLD → LLD → Redirect Flow → Cache → Analytics → Failure Cases → Java Implementation → Follow-up Questions

Think of **bit.ly / tinyurl.com**:

```
Long URL
https://www.example.com/products/electronics/phones?id=98213&ref=newsletter&utm_source=mail
        ↓
Shorten API
        ↓
https://sho.rt/aZ3k9Qp
        ↓
User clicks short URL
        ↓
Redirect to the original long URL
```

## Index

1. Problem Statement
2. Functional Requirements
3. Non-Functional Requirements
4. Capacity Estimation
5. Core Entities
6. Database Design (SQL vs NoSQL)
7. API Design
8. Short Key Generation: the heart of the problem
9. Approach 1: Hash + Truncate
10. Approach 2: Random Base62
11. Approach 3: Global Counter + Base62
12. Approach 4: Distributed ID (Snowflake)
13. Approach 5: Key Generation Service (KGS)
14. Approach 6: ZooKeeper Range Allocation
15. Base62 Encoding
16. High-Level Architecture
17. Write Flow (Shorten)
18. Read Flow (Redirect)
19. 301 vs 302
20. Caching
21. Database Scaling (Replication + Sharding)
22. Custom Alias
23. Expiry and Cleanup
24. Analytics Pipeline (Kafka)
25. Rate Limiting and Abuse Protection
26. Security
27. Final Production Architecture
28. Java LLD
29. Concurrency Practice
30. Spring Boot Structure
31. SOLID / Design Patterns
32. Failure Scenarios
33. Advanced (Interview Bonus) Topics
34. Interview Follow-Up Questions
35. Extra Topics to Study
36. Java Practice Roadmap
37. 30-Second Interview Explanation

---

## 1. Problem Statement

Design a service where users can:

1. Give a long URL and get a short URL.
2. Open the short URL and get redirected to the long URL.
3. Optionally choose a custom alias (`sho.rt/my-sale`).
4. Optionally set an expiry time.
5. See basic click analytics (clicks, country, device, referrer).

Reads massively outnumber writes. This is a **read-heavy system**, and that single fact drives most design decisions.

## 2. Functional Requirements

### 2.1 Create Short URL

```
Input:  longUrl, [customAlias], [expiresAt]
Output: shortUrl
```

- Same long URL submitted twice: either return the same short URL or a new one. **Decide and state it in the interview.** (Bitly gives a new one per user/request; a simple design can dedupe.)

### 2.2 Redirect

```
GET /aZ3k9Qp
   ↓
HTTP 302 → Location: original long URL
```

### 2.3 Custom Alias

User picks the key themselves. We must check uniqueness.

### 2.4 Expiry

URL stops working after a given time (default, say, 5 years).

### 2.5 Analytics (optional but commonly asked)

Click count, timestamp, country, device, referrer.

### 2.6 Delete / Update

Owner can disable or delete their short URL.

### Out of scope (say this out loud in the interview)

Full user management, billing, QR codes, link-in-bio pages. Mention them as extensions.

## 3. Non-Functional Requirements

### Availability

Redirect must be **highly available**. If redirect is down, every link on the internet that points to us is broken.

### Low Latency

Redirect should feel instant: target p99 under \~100 ms at the service.

### Scalability

Billions of URLs, thousands of redirects per second (peak much higher).

### Consistency (Uniqueness on the write path)

Uniqueness is really a consistency requirement, and it applies to write operations: two different long URLs must never get the same short key, so key creation needs strong consistency (unique constraint / atomic conditional insert). Reads (redirects) can stay eventually consistent and favour availability.

### Unpredictability (nice to have)

Short keys should not be guessable or sequentially enumerable (someone could scrape all links).

### Durability

A created URL must never be lost.

### Eventual consistency is fine for analytics

A click count that is a few seconds stale is acceptable.

### CAP trade-off: decide it while writing the NFRs

Yes, this is the best place to bring up CAP in an interview. Availability and consistency are non-functional requirements, and CAP is the reasoning that tells you which one wins for each operation. Say it as part of the NFRs, then use it to justify the design later:

- **Redirect (reads): availability over consistency (AP).** A slightly stale mapping is better than an error.
- **Key creation (writes): consistency over availability (CP-like).** Two long URLs must never get the same key, so a write may briefly fail or retry during a failure rather than risk a duplicate.
- **Analytics: eventual consistency** is fine.

Why: CAP says that during a network partition you must choose between consistency and availability. Redirects can serve a slightly stale mapping (a URL deleted 2 seconds ago still redirects) rather than fail, but we can never hand out the same key to two long URLs.

## 4. Capacity Estimation

Always do this in the interview. Even rough numbers show maturity. State assumptions first.

### Assumptions

```
New URLs per month    = 100 million
Read : Write ratio    = 100 : 1
Retention             = 5 years
Avg record size       = ~500 bytes
```

**Why not start from daily active users (DAU)?** Because here the numbers that drive the design are *URLs created* and *redirects served*, and DAU is only an indirect way to get to them. Also, most redirect traffic comes from people who never use our product: they just click a short link someone else shared, so they are not our "active users". Key length depends only on the **total number of URLs stored** (writes × retention), not on DAU at all.

You can start from DAU if the interviewer prefers it. It is just one more step before the same calculation:

```
DAU (creators)          = 10 million
Avg URLs created / user = 0.35 per day   (most users create none)
New URLs per day        = ~3.5 million
New URLs per month      = ~100 million   (same as above)
Read : Write = 100 : 1  (clicks come from the wider public, not only DAU)
```

Rule of thumb: **DAU matters for user-centric systems** (feeds, chat, ticket booking). For a URL shortener, state the writes per month and the read:write ratio directly.

**Does the number of write servers depend on DAU?** Indirectly, yes. Server count is driven by *peak QPS*, and if you start from DAU, the write QPS comes from DAU × URLs created per user per day × a peak factor. Two separate reasons to have more than one write server:

- **Capacity (depends on DAU / write QPS):** at \~40 writes/sec average (\~120 at 3× peak), one modest server could handle the load. If DAU grows 100×, write QPS grows with it and you add instances behind the load balancer.
- **Availability (does not depend on DAU):** even at tiny traffic, run at least 2-3 instances across zones so one crash does not stop link creation. This is redundancy, not scaling.

The read service (redirects) is the one that needs the most scaling, because its QPS is \~100× the write QPS.

### Traffic

**What is QPS?** QPS means *Queries Per Second*: how many requests the system receives every second. It is the basic unit for sizing servers, caches and databases. (You will also see RPS, requests per second, which means the same thing.) Here, *write QPS* is how many short URLs are created per second, and *read QPS* is how many redirects are served per second.

```
QPS = total requests in a period / seconds in that period

Example: 100M new URLs per month
  seconds in a month = 30 × 24 × 3600 = ~2.6 million
  write QPS = 100M / 2.6M = ~40 per second
```

Related terms used in these notes:

- **Throughput:** how much work a system completes per unit of time. QPS is the most common unit of throughput for request-based systems. Throughput can also be measured in data volume (MB/s), as in the Bandwidth estimate below. Two ways to read it: the QPS we *expect* (load) vs the QPS a server can actually *handle* (capacity); servers needed = peak QPS / QPS per server.
- **Average vs peak QPS:** traffic is not flat. We design for the busiest moment, so we multiply the average by a peak factor (commonly 2-3×).
- **DAU:** Daily Active Users, the number of distinct users who use the product in a day.
- **p99 latency:** 99% of requests finish at least this fast. It shows the slow tail that average latency hides.

```
Writes/sec   = 100M / (30 × 24 × 3600) ≈ 100M / 2.6M ≈ 40 writes/sec
Reads/sec    = 40 × 100 = 4,000 redirects/sec (average)
Peak (3×)    ≈ 12,000 redirects/sec
```

### Storage

```
URLs in 5 years = 100M × 12 × 5 = 6 billion
Storage         = 6B × 500 bytes = 3 TB
```

3 TB over 5 years is small for modern infra. The challenge is **read QPS and key generation**, not storage.

### Key length

```
Base62 characters: a-z, A-Z, 0-9 = 62

62^6 = ~56.8 billion
62^7 = ~3.5 trillion
```

6 billion URLs need more than 62^5 (916M) and fit in 62^6 (56.8B). Using **7 characters** gives huge headroom (3.5T), so 7 is the safe standard choice.

### Cache size (80/20 rule)

```
Daily reads        = 4,000 × 86,400 ≈ 345 million
Cache top 20%      = ~69 million entries
Memory             = 69M × 500 B ≈ 35 GB
```

That fits in one beefy Redis node or a small cluster.

### Bandwidth

```
Incoming (writes) = 40 × 500 B   = 20 KB/s
Outgoing (reads)  = 4,000 × 500 B = 2 MB/s
```

Tiny. The redirect response is just a small header.

## 5. Core Entities

```
User
ShortUrl (UrlMapping)
ClickEvent (Analytics)
KeyRange / UnusedKey (for KGS)
```

```
User 1 ───────< * ShortUrl 1 ───────< * ClickEvent
```

## 6. Database Design

### 6.1 urls table

```
urls
-------------------------
short_key      VARCHAR(10) PK
long_url       VARCHAR(2048)
user_id        BIGINT NULL
created_at     TIMESTAMP
expires_at     TIMESTAMP NULL
is_active      BOOLEAN
click_count    BIGINT (optional, better kept in analytics store)
```

`short_key` is the primary key. Redirect is a pure **primary-key lookup**, the fastest possible read.

**Why not a separate auto-increment `id` as the primary key?** You could add one, and it is a valid design. But here it gives us nothing, because `short_key` already has the two properties a primary key needs: it is **unique** and it is the **only thing we look rows up by** (a redirect arrives with just the short key). Using it directly has these advantages:

- **One lookup instead of two.** With a separate `id`, redirect must search the `short_key` index to find the id, then fetch the row (in many databases, such as MySQL InnoDB, a secondary index points to the primary key, which is a second lookup). With `short_key` as the primary key it is a single lookup, which matters at \~4,000+ redirects/sec.
- **Uniqueness is guaranteed in one place.** The primary key constraint is what stops two URLs from getting the same key (and it is what makes the atomic conditional insert work). With a separate id you would still need a second unique index on `short_key`, so the id would be an extra column and an extra index.
- **Sharding is simpler.** We shard by `short_key`, so the shard key and the primary key are the same value, and a redirect goes to exactly one shard.
- **Key-value stores need it.** In DynamoDB or Cassandra the partition key is what you look up by, so `short_key` is the natural key there.

When a separate numeric id *does* make sense: with the counter approach, the id *is* the key (`short_key = Base62(id)`), so it is the same thing in a different form. Custom aliases cannot be derived from a counter, which is why we store `short_key` as text. Interview answer: "the short key is already unique and is the lookup key, so I use it as the primary key; a surrogate id would only add an extra index and an extra lookup."

### 6.2 users table

```
users
-------------------------
id PK
name
email UNIQUE
api_key
created_at
```

### 6.3 click_events table (analytics, separate store)

```
click_events
-------------------------
event_id
short_key
clicked_at
country
device
browser
referrer
ip_hash
```

### 6.4 SQL or NoSQL?

Honest answer: **either works**, justify your pick.

Why a NoSQL key-value store (DynamoDB / Cassandra / MongoDB) fits well:

- Access pattern is only `get(short_key)` and `put(short_key, long_url)`.
- No joins, no complex transactions.
- Horizontal scaling and sharding are built in.
- Billions of rows, simple schema.

Why SQL (PostgreSQL / MySQL) also works:

- 3 TB over 5 years is manageable with sharding.
- `PRIMARY KEY` / `UNIQUE` constraint gives a free uniqueness guarantee.
- Easy to run user-level queries ("all my links").

**Recommended answer:** Start with a key-value/NoSQL store for the mapping because the access pattern is a pure key lookup at huge scale, and keep a relational DB for user and account data. If the interviewer pushes back, say SQL with sharding by `short_key` is equally valid.

### 6.4.1 How to decide: the questions that drive the choice

Interviewers are not looking for one "correct" database. They want to see that you pick from the **requirements**. Ask these questions out loud and answer each one for this system:

1. **What is the access pattern?** Redirect is `get(short_key)`, create is `put(short_key, long_url)`. No joins, no range scans, no multi-row transactions. This is a pure key-value workload, which is exactly what NoSQL key-value stores are built for.
2. **Do we need relationships or complex queries?** For the URL mapping, no. For users, billing and "all my links", yes, a little. This is why the answer is often *both*, each used where it fits.
3. **Do we need multi-row ACID transactions?** No. The one thing we need is an atomic "insert if this key does not exist", and both SQL (`PRIMARY KEY` violation) and NoSQL (DynamoDB conditional put, Cassandra `IF NOT EXISTS`) provide it on a single record.
4. **How much data and how fast does it grow?** \~3 TB over 5 years with \~4K reads/sec. That is not huge, so a sharded SQL setup can handle it too. The gap between the two options is smaller than people think at this scale.
5. **What consistency do we need?** Strong for key creation, eventual is fine for redirects (see the CAP trade-off in section 3). Cassandra and DynamoDB let you choose consistency per operation.
6. **What does the team already run?** In a real company, operational familiarity is a legitimate deciding factor. Saying so shows maturity.

### 6.4.2 What to choose

**My pick: a key-value NoSQL store (for example DynamoDB or Cassandra) for the URL mapping, plus a relational DB (PostgreSQL/MySQL) for users and account data.**

Why this wins on the requirements above:

- **The workload is a hash lookup.** Partition key = `short_key` gives O(1) reads and writes, spread evenly across nodes because random keys distribute uniformly.
- **Scaling is built in.** Adding nodes redistributes partitions automatically (consistent hashing under the hood). With SQL we would build and operate sharding ourselves: routing, resharding, cross-shard operations.
- **High availability by default.** Data is replicated across nodes and zones, with no single primary whose failure blocks writes. That fits our availability-first requirement for redirects.
- **Built-in TTL** deletes expired URLs for us (see the Expiry section), so we do not need a cleanup job scanning the table.
- **Cheap, predictable latency** for single-key reads, which supports the p99 target.

### 6.4.3 Be honest about the trade-offs of your choice

Naming the downsides yourself is what makes the answer sound senior. Do not oversell:

- **Limited querying.** "List all my links" or "find links created last week" is awkward in a pure key-value store. Fix: a secondary index (DynamoDB GSI on `user_id`) or keep that in the relational DB.
- **Weaker transactions.** No multi-row ACID across keys. We do not need it, but say that you checked.
- **Eventual consistency on reads** unless you pay for strongly consistent reads. We accept this for redirects, and the cache makes it even less noticeable.
- **Operational cost and learning curve** for running Cassandra yourself. A managed service (DynamoDB) reduces this at a price.
- **Vendor lock-in** if you choose a cloud-specific store.

### 6.4.4 When you would choose SQL instead

- The team is small and already runs PostgreSQL/MySQL well, and 3 TB with a cache in front is comfortably within reach.
- You expect rich queries (reporting, filtering by many fields, joins with users/teams) on the same data.
- You want a simpler system first and plan to move later. Starting simple is a perfectly defensible engineering choice, as long as you say how you would scale (read replicas, then sharding by `short_key`).

### 6.4.5 How to say it in the interview (script)

> "The core access pattern is a key lookup: given a short key, return the long URL, and we have around a hundred reads for every write. There are no joins or multi-row transactions, and the one correctness rule is that a key is never reused, which I can enforce with an atomic conditional insert on a single record. That is a textbook fit for a key-value store like DynamoDB or Cassandra, which gives me horizontal scaling, replication and TTL expiry out of the box. I would keep users and account data in a relational database because that part is relational and low volume. The trade-off is limited querying, so for 'my links' I would add a secondary index. That said, at 3 TB SQL with sharding and a cache would also work, so if the team already runs PostgreSQL well I would be comfortable starting there and sharding by short key later."

Why this answer impresses:

- It **starts from the access pattern and requirements**, not from a favourite technology.
- It **names the trade-offs** of the choice and how to mitigate them.
- It **shows you know the alternative** and when it is the better call, which signals judgement instead of memorised answers.
- It uses numbers from the capacity estimate (3 TB, \~4K reads/sec), so the decision is visibly tied to the earlier work.

### 6.4.6 If the interviewer pushes back

- **"Why not just MySQL?"** It works. At this scale I can shard by `short_key` and put a cache in front. I prefer key-value because sharding, replication and failover come built in, so we spend less effort operating it.
- **"NoSQL cannot guarantee uniqueness."** It can on a single key: DynamoDB conditional writes and Cassandra lightweight transactions (`IF NOT EXISTS`) are atomic per record. Note that Cassandra's lightweight transactions are slower than normal writes, but our write rate (\~40/sec) is tiny, so it is fine. Also, with a KGS the key is already unique before we insert.
- **"What about analytics queries?"** They do not run on this store. Click events go through Kafka to a separate analytics store.
- **"What if requirements change and you need complex queries?"** Stream changes to a search or analytics store (for example via CDC) for those queries, and keep this store for fast key lookups.

### 6.5 Indexes

- Primary index on `short_key` (default).
- Index on `(user_id, created_at)` for "my links" listing.
- Index on `expires_at` for the cleanup job (or use TTL feature in the store).
- Optional unique index on `long_url` hash if you want dedupe (usually skipped, since long URLs are large).

## 7. API Design

### Create short URL

```
POST /api/v1/urls
Authorization: Bearer <token or api_key>

{
  "longUrl": "https://www.example.com/very/long/path?x=1",
  "customAlias": "my-sale",          // optional
  "expiresAt": "2027-01-01T00:00:00Z" // optional
}
```

Response `201 Created`:

```
{
  "shortUrl": "https://sho.rt/aZ3k9Qp",
  "shortKey": "aZ3k9Qp",
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

Errors: `400` invalid URL, `409` alias already taken, `429` rate limited.

### Redirect

```
GET /{shortKey}
```

Responses: `302 Found` with `Location` header, `404` not found, `410 Gone` if expired/deleted.

### Get details / analytics

```
GET /api/v1/urls/{shortKey}
GET /api/v1/urls/{shortKey}/stats?from=...&to=...
```

### Delete

```
DELETE /api/v1/urls/{shortKey}
```

### List my links

```
GET /api/v1/users/me/urls?page=0&size=20
```

### Idempotency (making retries safe)

**What it means.** An operation is **idempotent** if doing it once or many times leaves the system in the same state. Networks fail and clients retry: a mobile app sends "create short URL", the connection drops before the response arrives, and the app sends it again. Without idempotency the user may end up with two short links, and a payment-style API would charge twice.

**Where each operation stands in this design:**

- **Redirect (`GET`)** is naturally idempotent: asking again returns the same long URL. The only side effect is the click counter, which we treat as eventually consistent and de-duplicate by `eventId` in analytics (Kafka delivers at-least-once, so the same event can arrive twice).
- **Delete (`DELETE`)** is idempotent: the first call deletes, later calls find nothing to delete and still leave the same end state (we return `204` or `404`, either is fine as long as the state is the same).
- **Create (`POST`)** is **not** idempotent by default: each call generates a new key and inserts a new row. This is the one we must fix.
- **Custom alias create** is already safe against races because of the atomic conditional insert, but a retry by the same user would get `409 Conflict` for their own alias. With an idempotency key we can return the original success instead.

**How we make create idempotent.** The client sends a unique `Idempotency-Key` header (a UUID it generates once per logical request and reuses on every retry). The server stores `idempotencyKey -> response` (in Redis or a DB table with a TTL such as 24 hours). Flow:

```
POST /api/v1/urls
Idempotency-Key: 7f3c-91ab-...

1. Look up the key in the idempotency store
     found    -> return the SAME stored response (no new short URL)
     not found-> continue
2. Generate key + atomic conditional insert (as before)
3. Store idempotencyKey -> response, return it
   (steps 1 and 3 must be atomic, e.g. Redis SET NX, otherwise two parallel
    retries could both pass step 1)
```

There is also a lighter option for users who just paste the same URL twice: de-duplicate by `hash(userId + longUrl)` and return the existing short URL (see the dedupe point in the advanced topics). That is a business choice, while the `Idempotency-Key` is about safe retries of one request.

Tested Java sketch (a `ConcurrentHashMap` stands in for Redis; `computeIfAbsent` runs the creation only once per key, even when 20 retries arrive together):

```
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class IdemDemo {
    static final AtomicLong counter = new AtomicLong(1000);
    // idempotencyKey -> the short url we already created for it
    static final Map<String, String> processed = new ConcurrentHashMap<>();

    static String createShortUrl(String idempotencyKey, String longUrl) {
        // computeIfAbsent runs the lambda only ONCE per key, even if many retries arrive together
        return processed.computeIfAbsent(idempotencyKey, k -> {
            String shortKey = "key" + counter.incrementAndGet();   // pretend this is generate + insert
            return "https://sho.rt/" + shortKey;
        });
    }

    public static void main(String[] args) throws Exception {
        System.out.println(createShortUrl("req-1", "https://example.com/a"));
        System.out.println(createShortUrl("req-1", "https://example.com/a"));  // client retry
        System.out.println(createShortUrl("req-2", "https://example.com/a"));  // a genuinely new request

        // 20 threads retry the same request at once
        Thread[] ts = new Thread[20];
        for (int i = 0; i < 20; i++) {
            ts[i] = new Thread(() -> createShortUrl("req-3", "https://example.com/b"));
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        System.out.println("short urls created: " + (counter.get() - 1000) + " (expected 3: req-1, req-2, req-3)");
    }
}
```

Output when run:

```
https://sho.rt/key1001
https://sho.rt/key1001
https://sho.rt/key1002
short urls created: 3 (expected 3: req-1, req-2, req-3)
```

The retry of `req-1` returned the same short URL, and 20 simultaneous retries of `req-3` created only one.

Where else idempotency shows up in these notes: the atomic conditional insert (a retried insert of the same key is rejected, not duplicated), KGS key hand-out (a retry after a crash wastes a key but never produces a duplicate), and Kafka consumers (de-duplicate by `eventId`).

Interview line: "Reads and deletes are naturally idempotent. For create I accept an Idempotency-Key header and store the first response for it, so a client retry returns the same short URL instead of creating a second one."

## 8. Short Key Generation: the heart of the problem

This is **the** question the interviewer is really asking. Every approach has trade-offs, and a strong answer walks through 2-3 of them and picks one with reasons.

Goals for the key:

```
1. Unique (no collisions)
2. Short (7 chars)
3. Fast to generate
4. Works across many servers
5. Hard to guess (ideally)
```

Approaches covered next:

```
Hash + Truncate
Random Base62
Counter + Base62
Snowflake
Key Generation Service (KGS)
ZooKeeper range allocation
```

## 9. Approach 1: Hash + Truncate

```
longUrl → MD5 / SHA-256 → take first 8 bytes → mod 62^7 → Base62 → 7 chars
```

### 9.1 What is a hash function (MD5, SHA-256)?

A hash function takes input of **any size** (a word, a URL, a whole file) and produces a **fixed-size fingerprint**. Think of it as a blender: the same fruit always gives the same smoothie, but you cannot turn the smoothie back into the fruit.

- **Deterministic:** the same input always gives the same output.
- **Fixed size:** MD5 always gives 128 bits (32 hex characters). SHA-256 always gives 256 bits (64 hex characters), no matter how long the input is.
- **Avalanche effect:** changing one character completely changes the output, so the outputs look random and spread evenly.
- **One-way:** you cannot recover the input from the hash.
- **Collisions exist:** infinitely many inputs map to a limited number of outputs, so two different inputs can (rarely) give the same hash. A good hash makes this very unlikely. When we *shorten* the hash to 7 characters, collisions become much more likely, which is the main problem with this approach.

Real outputs (run in Java):

```
MD5("hello")    = 5d41402abc4b2a76b9719d911017c592
MD5("hello!")   = 5a8dd3ad0756a93ded72b823b19dd877   (one character added, totally different)
SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
```

**MD5 vs SHA-256:** MD5 is faster and shorter but broken for security (collisions can be crafted on purpose). SHA-256 is slower and still considered secure. For a URL shortener we only need a well-spread number, not security, so either works. A fast non-cryptographic hash like MurmurHash is even better for this job.

### 9.2 How it works step by step

```
1. longUrl                       = "https://example.com/a"
2. SHA-256(longUrl)              = 32 bytes (256 bits)
3. Take the first 8 bytes        = a 64-bit number
4. number mod 62^7               = a number below 3.5 trillion (fits in 7 Base62 chars)
5. Base62-encode, pad to 7 chars = "pHMxsnU"
```

Step 4 is important: it forces the number into exactly the range that 7 Base62 characters can represent.

### 9.3 Java code (tested)

Simple version: three small methods, one per step, using only built-in Java classes.

```
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ShortKeyDemo {

    static final String CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final long SPACE = 3_521_614_606_208L; // 62^7 = all possible 7-char keys

    // Step 1: URL -> SHA-256 -> first 8 bytes as a long number
    static long hashToNumber(String text) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha.digest(text.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(hash).getLong();   // reads the first 8 bytes
    }

    // Step 2: number -> Base62 text (repeatedly divide by 62)
    static String toBase62(long number) {
        String result = "";
        while (number > 0) {
            result = CHARS.charAt((int) (number % 62)) + result;
            number = number / 62;
        }
        return result;
    }

    // Step 3: put it together -> always a 7-character key
    static String shortKey(String longUrl) throws Exception {
        long number = hashToNumber(longUrl);
        long fitted = Math.floorMod(number, SPACE);   // keep it below 62^7
        String key = toBase62(fitted);
        while (key.length() < 7) {                    // pad with 0s on the left
            key = "0" + key;
        }
        return key;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(shortKey("https://example.com/a"));
        System.out.println(shortKey("https://example.com/a"));  // same input, same key
        System.out.println(shortKey("https://example.com/b"));  // different input, different key
        System.out.println(shortKey("https://example.com/a" + "1")); // salted for a collision retry
        System.out.println(Math.pow(62, 7));
    }
}
```

Output when run:

```
pHMxsnU     (https://example.com/a)
pHMxsnU     (same URL again, same key: deterministic)
I9jDesz     (https://example.com/b, a different URL gives a different key)
lW8ugX3     (URL + "1" as a salt, used when the first key collides)
```

How to read it, one step at a time:

- `hashToNumber`: SHA-256 turns the URL into 32 bytes. `ByteBuffer.wrap(hash).getLong()` reads the first 8 of them as one big number.
- `toBase62`: keeps dividing the number by 62. Each remainder (0 to 61) picks one character from `CHARS`. This is the same idea as converting to binary, but with 62 symbols.
- `shortKey`: `floorMod(number, SPACE)` squeezes the number below 62^7 (3.5 trillion), which is the most that 7 characters can hold. Then it pads with `0` on the left if the result is shorter than 7.

### 9.4 Collision handling in code

```
String saveWithRetry(String longUrl) throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
        String salt = attempt == 0 ? "" : String.valueOf(attempt);
        String key = shortKey(longUrl + salt);
        // atomic conditional insert: returns false if the key already exists
        if (repository.saveIfAbsent(key, longUrl)) {
            return key;
        }
        // Key taken. It may be the SAME url (fine, reuse) or a different one (retry with a salt).
        if (longUrl.equals(repository.findByKey(key).getLongUrl())) {
            return key;
        }
    }
    throw new IllegalStateException("Could not generate a unique key");
}
```

Pros:

- Stateless, no coordination.
- Same long URL always gives the same key (natural dedupe).

Cons:

- **Collisions.** Truncating a hash makes collisions possible (birthday paradox).
- Collision handling means a DB check and retry.

Collision handling:

```
key = hash(longUrl)[0:7]
while exists(key):
    key = hash(longUrl + salt/counter)[0:7]
```

This costs an extra DB read on every write and gets worse as the table fills. Fine at 40 writes/sec, but not elegant.

Interview note: use a non-cryptographic fast hash like **MurmurHash** if you only need distribution. Security of the hash is not the concern here.

## 10. Approach 2: Random Base62

```
Generate 7 random base62 chars → check DB → insert
```

Collision probability with N existing keys out of 3.5T is N / 3.5T. At 6B keys it is about 0.17% per attempt, and a retry fixes it.

Pros: unpredictable keys, trivial to implement.

Cons: a DB existence check (or conditional insert) on every write. As the keyspace fills, retries increase.

Best practice: do an **atomic conditional insert** instead of check-then-insert:

```
INSERT INTO urls (short_key, long_url) VALUES (?, ?)
-- if unique violation → generate a new key and retry
```

This removes the race between "check" and "insert".

### 10.1 Two separate jobs: generate and insert

This approach has two steps, and they do different things:

- **Generate:** pick 7 random Base62 characters. This only produces a *candidate*. Nothing guarantees it is unused, so it is just a guess.
- **Insert:** try to save `key -> longUrl` in the database. This is where we find out whether the guess was free. If the key already exists, the database refuses, and we go back to step 1 with a new random key.

```
loop (max 5 times):
    key = generate 7 random chars          # guess
    saved = insert(key, longUrl)           # ask the DB: "save this only if the key is free"
    if saved: return key                   # free, it is ours
    # else: key was taken, try another random key
```

### 10.2 What is an atomic conditional insert?

**Conditional** means "save only if this key does not already exist". **Atomic** means the check and the save happen as **one indivisible step**: nothing else can run in between them.

Why this matters: the tempting way is two steps, **check, then insert**. That has a race condition:

```
WRONG (check-then-insert)

User A: exists("aZ3k9Qp")?  -> no, it is free
User B: exists("aZ3k9Qp")?  -> no, it is free      (A has not saved yet)
User A: insert("aZ3k9Qp", urlA)  -> saved
User B: insert("aZ3k9Qp", urlB)  -> overwrites A, or creates a duplicate!

RIGHT (atomic conditional insert)

User A: insert-if-absent("aZ3k9Qp", urlA)  -> success
User B: insert-if-absent("aZ3k9Qp", urlB)  -> FAILED (key exists), so B generates a new key
```

Two users can both see "free" in the wrong version because there is a gap between the check and the save. In the atomic version there is no gap: the database decides who goes first, and exactly one wins.

How to get this in real systems:

- **SQL:** a plain `INSERT` into a table where `short_key` is the `PRIMARY KEY`. The database rejects a duplicate with a unique-violation error, and that rejection *is* the "condition". Do not run a `SELECT` first.
- **DynamoDB:** `PutItem` with `attribute_not_exists(short_key)`.
- **Cassandra:** `INSERT ... IF NOT EXISTS`.
- **Redis:** `SET key value NX`.
- **Java in memory:** `ConcurrentHashMap.putIfAbsent(key, value)`.

### 10.3 Java code (tested)

A `ConcurrentHashMap` stands in for the database so you can run it anywhere. `putIfAbsent` behaves like the atomic conditional insert.

```
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RandomKeyDemo {

    static final String CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final SecureRandom random = new SecureRandom();

    // Our "database": key -> long URL. ConcurrentHashMap is safe for many threads.
    static final Map<String, String> db = new ConcurrentHashMap<>();

    // GENERATE: pick 7 random characters
    static String generateRandomKey() {
        String key = "";
        for (int i = 0; i < 7; i++) {
            key = key + CHARS.charAt(random.nextInt(62));
        }
        return key;
    }

    // INSERT (atomic conditional insert):
    // "save only if this key is not already there", done as ONE step.
    // putIfAbsent returns null when it saved, or the existing value when the key was taken.
    static boolean saveIfAbsent(String key, String longUrl) {
        return db.putIfAbsent(key, longUrl) == null;
    }

    // GENERATE + INSERT together
    static String createShortUrl(String longUrl) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            String key = generateRandomKey();
            if (saveIfAbsent(key, longUrl)) {
                return key;                 // saved, we own this key
            }
            // key was already taken -> loop and try a new random key
        }
        throw new IllegalStateException("Could not find a free key");
    }

    public static void main(String[] args) throws Exception {
        System.out.println(createShortUrl("https://example.com/a"));
        System.out.println(createShortUrl("https://example.com/b"));

        // Race test: 100 threads all try to save the SAME key at the same time.
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[100];
        for (int i = 0; i < 100; i++) {
            final int n = i;
            threads[i] = new Thread(() -> {
                if (saveIfAbsent("SAMEKEY", "https://user" + n + ".com")) winners.incrementAndGet();
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        System.out.println("Threads that saved SAMEKEY: " + winners.get() + " (expected 1)");
    }
}
```

Output when run:

```
l03dnQk
kdVBdaU
Threads that saved SAMEKEY: 1 (expected 1)
```

The last line is the important one: 100 threads tried to save the same key at the same moment and exactly one succeeded. That is the atomic guarantee.

### 10.4 Same idea with a real SQL database (Spring/JDBC sketch)

```
public String createShortUrl(String longUrl) {
    for (int attempt = 1; attempt <= 5; attempt++) {
        String key = generateRandomKey();
        try {
            // short_key is the PRIMARY KEY, so a duplicate makes the DB throw
            jdbcTemplate.update(
                "INSERT INTO urls (short_key, long_url) VALUES (?, ?)", key, longUrl);
            return key;                              // inserted, key is ours
        } catch (DuplicateKeyException e) {
            // key already taken -> loop and try a new random key
        }
    }
    throw new IllegalStateException("Could not find a free key");
}
```

Interview line: "I never check first and insert second. I insert directly and let the primary key constraint reject duplicates, so the check and the write are one atomic operation and two users can never get the same key."

## 11. Approach 3: Global Counter + Base62

```
counter = 125000000
Base62(125000000) = "8Lbq0"  → pad/handle to 7 chars
```

A single counter gives a unique integer to every request, and the integer is converted to base62. **No collisions by construction.**

Pros: zero collisions, very short keys early on.

Cons:

- **Single point of failure and bottleneck** if one counter serves everything.
- **Predictable / sequential** keys, so people can enumerate every link. Mitigation: shuffle bits or apply a reversible permutation before encoding.

Making the counter distributed:

```
Redis INCR counter      (single node, atomic, fast, but SPOF without replication)
DB auto-increment       (works, but one primary is a bottleneck)
Multiple DB auto-increment with step (server 1 odd, server 2 even)
```

## 12. Approach 4: Distributed ID (Snowflake)

```
| 41 bits timestamp | 10 bits machine id | 12 bits sequence |
```

Each app server generates unique 64-bit IDs on its own, with no coordination at request time.

Pros: fully distributed, roughly time-ordered, no central counter.

Cons: a 64-bit number in base62 is **11 characters**, longer than 7. To fit 7 characters you need a smaller custom layout (fewer timestamp bits, fewer machine bits), which limits throughput per machine. Also still roughly ordered, so somewhat guessable.

Good to mention as an alternative, but for a 7-character requirement it is usually not the final pick.

## 13. Approach 5: Key Generation Service (KGS)

This is the approach most interviewers like, and the one the standard video and Grokking-style courses recommend.

Idea: **generate keys ahead of time**, store them, and hand them out on demand.

```
          ┌──────────────────────┐
          │ Key Generation       │
          │ Service (KGS)        │
          └──────────┬───────────┘
                     │ pre-generates random unique 7-char keys
                     ▼
          ┌──────────────────────┐
          │   Key DB             │
          │                      │
          │  unused_keys         │
          │  used_keys           │
          └──────────────────────┘
```

Tables:

```
unused_keys ( key VARCHAR(7) PK )
used_keys   ( key VARCHAR(7) PK )
```

### Flow

```
App Server needs a key
        ↓
Asks KGS (or fetches a batch)
        ↓
KGS moves key from unused → used (atomically)
        ↓
Returns key
        ↓
App Server stores (key → longUrl) in URL DB
```

### Important design details

1. **Batching:** app servers fetch e.g. 1,000 keys at once and keep them in memory. This avoids a network call per request.
2. **Atomic hand-out:** KGS must mark keys as used the moment it gives them out, so two servers never get the same key. Use a DB transaction, or `SELECT ... FOR UPDATE SKIP LOCKED`.
3. **If an app server crashes** with unused keys in memory, those keys are lost. With 3.5 trillion possible keys, losing a few thousand is fine.
4. **KGS is a single point of failure:** run a primary and a standby KGS, or shard the key space (each KGS instance owns a distinct key range, for example by first character).
5. **Keys are random**, so not guessable.

Pros: no collision checks at request time, fast, unpredictable keys, simple on the hot path.

Cons: extra component to run and keep highly available; needs a pre-population job.

## 14. Approach 6: ZooKeeper Range Allocation

A cleaner variation of the counter idea without a per-request central hit.

```
ZooKeeper holds: next_range_start

App Server 1 asks → gets range [1 .. 1,000,000]
App Server 2 asks → gets range [1,000,001 .. 2,000,000]
App Server 3 asks → gets range [2,000,001 .. 3,000,000]
```

Each server then increments **locally** inside its own range and base62-encodes the number. When a range is exhausted, it asks ZooKeeper for the next one.

Pros: no collisions, no per-request coordination, very fast.

Cons: keys within a server are sequential (guessable, so apply a bit-shuffle if it matters); a crashed server wastes the rest of its range (acceptable).

## 15. Base62 Encoding

```
Alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

encode(n):
    if n == 0 return "0"
    sb = ""
    while n > 0:
        sb = alphabet[n % 62] + sb
        n = n / 62
    return sb

decode(s):
    n = 0
    for ch in s:
        n = n * 62 + indexOf(ch)
    return n
```

Why not Base64? Base64 includes `+` and `/`, which are not URL-safe without escaping. Base62 avoids that. (Base64URL with `-` and `_` also works, but Base62 is the classic answer.)

## 16. High-Level Architecture

```
                 ┌─────────────────────┐
                 │       CLIENT        │
                 │  Browser / Mobile   │
                 └──────────┬──────────┘
                            │
                            ▼
                 ┌─────────────────────┐
                 │    CDN / DNS        │
                 └──────────┬──────────┘
                            │
                            ▼
                 ┌─────────────────────┐
                 │   Load Balancer     │
                 └──────────┬──────────┘
                            │
                            ▼
                 ┌─────────────────────┐
                 │    API Gateway      │
                 │  Auth, Rate Limit   │
                 └──────────┬──────────┘
                            │
            ┌───────────────┴────────────────┐
            ▼                                ▼
   ┌─────────────────┐              ┌─────────────────┐
   │  Write Service  │              │  Read Service   │
   │ (Shorten API)   │              │ (Redirect)      │
   └───┬─────────┬───┘              └───┬─────────┬───┘
       │         │                      │         │
       ▼         ▼                      ▼         ▼
     KGS      URL DB                  Redis     URL DB
                                      Cache   (read replicas)
                                                 
                  Read Service ── click event ──▶ Kafka ──▶ Analytics
```

Key idea: **separate read and write paths** (a light CQRS). Redirect traffic is 100× the write traffic, so it should scale independently.

## 17. Write Flow (Shorten)

```
Client
  ↓
POST /api/v1/urls
  ↓
Rate limiter + auth
  ↓
Validate URL (format, scheme http/https, length, not blocked)
  ↓
Custom alias?
   ├── YES → check/insert alias atomically → 409 if taken
   └── NO  → get key from KGS / range allocator
  ↓
Insert (key → longUrl, expiry, user) into URL DB
  ↓
(Optional) Warm the cache
  ↓
Return shortUrl
```

Validation checklist:

- Valid URL syntax and allowed scheme (`http`, `https` only, never `javascript:`).
- Max length (e.g., 2048).
- Not already a short URL of our own service (avoid redirect loops).
- Not on a malware/phishing blocklist.

## 18. Read Flow (Redirect)

This is the hot path. Memorize it.

```
Browser → GET sho.rt/aZ3k9Qp
            ↓
      Load Balancer
            ↓
       Read Service
            ↓
      Check Redis cache
       ┌────┴─────┐
     HIT         MISS
       │           │
       │           ▼
       │      Query URL DB (by primary key)
       │           │
       │      ┌────┴─────┐
       │    FOUND      NOT FOUND → 404
       │      │
       │      ▼
       │   Check expiry / is_active → 410 Gone if invalid
       │      │
       │      ▼
       │   Populate Redis (with TTL)
       │      │
       └──────┤
              ▼
   Publish click event to Kafka (async, non-blocking)
              ▼
   HTTP 302  Location: <longUrl>
```

Important: **publishing the analytics event must never block the redirect.** Fire and forget (or buffer in memory and batch).

### Negative caching

Cache "not found" results for a short time (e.g., 60 seconds). Otherwise attackers hammering random keys bypass the cache and hit the database every time (cache penetration).

## 19. 301 vs 302

A very common interview question.

**301 Moved Permanently**

- Browsers and CDNs cache the redirect.
- Later clicks may never reach our server.
- Less load, but **we lose analytics** and cannot change or expire the target.

**302 Found (temporary)**

- Browser asks our server every time.
- We can count every click, change the target, enforce expiry.
- More load on us.

Rule of thumb: **use 302 if analytics, expiry or editing matter** (Bitly-style products). Use 301 only if you purely care about minimal load and SEO link equity, and do not need tracking. A middle path is 302 with a `Cache-Control` max-age, if you accept some stale clicks.

## 20. Caching

Read-heavy workload with a skewed popularity distribution: a small set of URLs gets most of the traffic. Caching is the biggest win.

```
Request → Redis → hit → return
                → miss → DB → populate Redis
```

### Policy

- **Cache-aside** (lazy loading): read service checks Redis, falls back to DB, then fills Redis.
- **Eviction: LRU** (or LFU, which suits viral links well).
- **TTL:** e.g., 24 hours, and never longer than the URL's own expiry.
- **Size:** about 35 GB for the top 20% (from the estimate above).

### Cache layers

```
1. CDN / edge cache (only if using 301 or cacheable 302)
2. Local in-process cache (Caffeine) for the very hottest keys
3. Redis cluster (shared)
4. Database
```

### What is a local (in-process) cache?

A **local in-process cache** is a cache that lives **inside your application's own memory** (the JVM heap), in the same process as your code. It is just a data structure such as a `HashMap` or, better, an LRU map or a library like **Caffeine**. Reading from it is a plain method call: no network, no serialization.

Compare it with Redis, a **distributed (remote) cache**:

- **Speed:** local is nanoseconds to microseconds (a memory read). Redis is usually around 0.5 to 1 ms because every read is a network call.
- **Sharing:** each app server has its **own private copy**. Server A caching a link does not help server B. Redis is shared by all servers.
- **Size:** limited by the app's memory, so keep it small (for example the top few thousand hottest keys). Redis can hold tens of GB.
- **Consistency:** if a link is deleted, you must clear it from every server's local cache, otherwise some servers keep serving it until the entry expires. A short TTL (say 30 to 60 seconds) keeps this safe enough. Redis has one copy, so it is easier to invalidate.
- **Lost on restart:** when the server restarts, the local cache is empty again (this is called a cold start). Redis survives app restarts.

Why use it for a URL shortener: a viral link can get thousands of clicks per second. Even Redis can struggle with one very hot key on one node, and every request pays a network round trip. A tiny local cache in front of Redis absorbs most of those repeat reads with zero network cost.

```
Request for key "viral1"
   |
   v
Local cache (in the app)  -- hit --> return instantly
   | miss
   v
Redis (shared)            -- hit --> return, and also store in local cache
   | miss
   v
Database                  -- found --> store in Redis and local cache, return
```

A working example of an in-process LRU cache is in the Java LLD section: `CachedUrlRepository` keeps the most recently used links in a `LinkedHashMap` inside the app and only calls the database on a miss.

### Cache problems to mention

1. **Cache stampede (thundering herd):** a hot key expires and thousands of requests hit the DB at once. Fixes: request coalescing (single-flight), jittered TTLs, or a short per-key lock.
2. **Cache penetration:** requests for keys that do not exist. Fixes: negative caching, Bloom filter in front of the cache.
3. **Hot key:** one viral link overloads a single Redis shard. Fixes: local in-process cache, replicate that key across shards.
4. **Stale data after delete/update:** on delete, explicitly invalidate the cache entry.

## 21. Database Scaling

### Replication

```
        Primary (writes)
             │
   ┌─────────┼─────────┐
   ▼         ▼         ▼
Replica 1 Replica 2 Replica 3  (reads)
```

Reads go to replicas. Replica lag is acceptable for redirects (eventual consistency).

One catch: **read-after-write.** A user creates a link and immediately clicks it, but the replica has not caught up yet. Fix: on a miss from a replica, fall back to the primary once, or populate the cache on write.

### Sharding

3 TB is small, but for scale and write distribution, shard by `short_key`.

```
shard = hash(short_key) % N
```

Pros: even distribution, redirects touch exactly one shard.

Cons: resharding when N changes. Solution: **consistent hashing** (or a managed store like DynamoDB/Cassandra that does this for you).

Why not shard by `user_id`? A power user with millions of links creates a hot shard, and redirect lookups come with only a `short_key`, not a user id, so we would need a lookup directory first.

Why not shard by `long_url` hash? Redirect only has the short key, so you cannot find the shard.

### Consistent hashing (how it is used here)

**The problem with `hash(short_key) % N`.** With 3 shards a key goes to `hash % 3`. The moment you add a 4th shard the formula becomes `hash % 4`, and almost every key now maps to a different shard. All those rows would have to be moved, and until they are, redirects look in the wrong place and miss. In the test below, 74% of keys moved when going from 3 to 4 servers.

**The idea of consistent hashing.** Imagine the hash values laid out on a circle (a ring). Each server is placed at one or more points on that ring by hashing its name. To find the shard for a key, hash the key to a point on the ring and walk **clockwise** until you hit the first server. That server owns the key. When you add a new server, it takes over only the keys between itself and the previous server on the ring; every other key stays where it was. In the test, only 26% moved (the ideal is 1/N = 25%).

```
            S1
        .-'      '-.
      /              \
   S3                  key "aZ3k9Qp" lands here
     \              /      and walks clockwise to S2
        '-.      .-'
            S2
Add S4 between S1 and S2: only keys in that small arc move to S4.
```

**Virtual nodes.** With only one point per server the arcs can be very uneven, so one server may own a big slice of the ring. The fix is to place each server at many points (for example 100 "virtual nodes" per server, named `S1#0`, `S1#1` and so on). The slices average out and the load becomes even. It also means that when a server fails, its keys spread across all the others instead of dumping onto a single neighbour.

**Where it appears in this system.** Three places: (1) sharding the URL database by `short_key`, so we can add shards without moving everything; (2) the Redis cache cluster, where the cache node for a key is chosen with consistent hashing so adding a node does not wipe most of the cache (a cache stampede on the database); (3) managed stores like DynamoDB and Cassandra use it internally to place partitions, which is why we said they scale "out of the box".

Tested Java sketch (a `TreeMap` is the ring, and `ceilingKey` is the "walk clockwise" step):

```
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class RingDemo {

    // The ring: hash position -> server name (TreeMap keeps positions sorted)
    static class ConsistentHashRing {
        private final TreeMap<Long, String> ring = new TreeMap<>();
        private final int virtualNodes;

        ConsistentHashRing(int virtualNodes) { this.virtualNodes = virtualNodes; }

        static long hash(String text) {
            try {
                byte[] d = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
                return ByteBuffer.wrap(d).getLong() & Long.MAX_VALUE; // keep it positive
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        void addServer(String server) {
            for (int i = 0; i < virtualNodes; i++) {
                ring.put(hash(server + "#" + i), server);   // one server, many points on the ring
            }
        }

        void removeServer(String server) {
            for (int i = 0; i < virtualNodes; i++) {
                ring.remove(hash(server + "#" + i));
            }
        }

        // go clockwise from the key's position to the first server point
        String getServer(String key) {
            Long position = ring.ceilingKey(hash(key));
            if (position == null) position = ring.firstKey();   // wrap around the circle
            return ring.get(position);
        }
    }

    public static void main(String[] args) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10000; i++) keys.add("key" + i);

        // 1) Normal hashing: server = hash % N
        int moved = 0;
        for (String k : keys) {
            long h = ConsistentHashRing.hash(k);
            if (h % 3 != h % 4) moved++;                        // 3 servers -> 4 servers
        }
        System.out.println("hash % N      : " + moved * 100 / keys.size() + "% of keys moved when going 3 -> 4 servers");

        // 2) Consistent hashing
        ConsistentHashRing ring = new ConsistentHashRing(100);
        ring.addServer("S1"); ring.addServer("S2"); ring.addServer("S3");
        Map<String, String> before = new HashMap<>();
        for (String k : keys) before.put(k, ring.getServer(k));

        ring.addServer("S4");
        int movedRing = 0;
        Map<String, Integer> load = new TreeMap<>();
        for (String k : keys) {
            String now = ring.getServer(k);
            if (!now.equals(before.get(k))) movedRing++;
            load.merge(now, 1, Integer::sum);
        }
        System.out.println("consistent    : " + movedRing * 100 / keys.size() + "% of keys moved when going 3 -> 4 servers");
        System.out.println("load per server after adding S4: " + load);
    }
}
```

Output when run:

```
hash % N      : 74% of keys moved when going 3 -> 4 servers
consistent    : 26% of keys moved when going 3 -> 4 servers
load per server after adding S4: {S1=2564, S2=2522, S3=2233, S4=2681}
```

Interview line: "I shard by short key using consistent hashing with virtual nodes, so adding or removing a shard moves only about 1/N of the keys instead of nearly all of them, and the load stays even."

### Range-based sharding by first character

Another option when KGS owns key ranges: key starts with `a-k` → shard 1, and so on. Simple, but risks uneven load.

## 22. Custom Alias

```
POST { longUrl, customAlias: "my-sale" }
```

Steps:

1. Validate the alias (length 4-20, allowed characters `[a-zA-Z0-9-_]`, not a reserved word like `api`, `admin`, `login`, `health`).
2. Do an **atomic conditional insert**. If it exists, return `409 Conflict`.
3. Never rely on check-then-insert, because two users could race for the same alias.

Custom aliases live in the same keyspace as generated keys. Problem: a user could pick an alias that KGS would later hand out. Fixes: keep a separate namespace or prefix for custom aliases, or have KGS skip keys that already exist (check on use), or reject custom aliases that match the generated pattern.

## 23. Expiry and Cleanup

### Lazy expiry

At redirect time, check `expires_at`. If expired, return `410 Gone` and optionally delete.

### Active cleanup

Background job (cron / scheduled worker) deletes expired rows in small batches, off-peak, so it does not hurt production traffic.

```
DELETE FROM urls WHERE expires_at < now() LIMIT 1000;
```

If using DynamoDB or Cassandra, use the **built-in TTL** feature instead.

### Key recycling

Should expired keys be reused? With 3.5T keyspace, usually **no**. Reusing keys risks an old link silently pointing to a new, unrelated destination (a security and trust problem). If you do recycle, wait a long cooling period and return them to the KGS unused pool.

### Cache interaction

Cache TTL must be `min(default TTL, time until expiry)`.

## 24. Analytics Pipeline (Kafka)

Redirect must stay fast, so analytics are fully asynchronous.

```
Read Service
     │
     │ click event {shortKey, ts, ip, userAgent, referrer}
     ▼
   Kafka  (topic: click-events)
     │
     ├──────────────┬──────────────────┐
     ▼              ▼                  ▼
 Stream job     Raw event store     Fraud / bot
 (Flink/Spark)  (S3 / data lake)    detection
     │
     ▼
 Aggregated store (ClickHouse / Cassandra / Druid)
     │
     ▼
 Analytics API → Dashboard
```

Why Kafka here:

- Absorbs spikes (a viral link) without hurting redirects.
- Decouples redirect service from analytics.
- Multiple consumers can process the same stream.
- Replayable if a consumer has a bug.

Design details:

- Partition by `short_key` so events for one link are ordered.
- Enrich events (geo-IP, device parsing) in the consumer, **not** in the redirect path.
- Counters: aggregate in the stream job (per minute buckets) instead of incrementing a DB row per click (hot row problem).
- Accept loss of a tiny fraction of events if the producer is fire-and-forget, or use at-least-once with dedupe if exact counts matter.
- Hash or truncate IPs for privacy (GDPR).

## 25. Rate Limiting and Abuse Protection

Public URL shorteners are heavily abused for spam and phishing.

### Rate limiting

- Per user / API key / IP on the **create** endpoint (e.g., 100 per hour for free tier).
- Looser limits on redirect, but still protect against scraping and DDoS.
- Algorithms: token bucket or sliding window, stored in Redis.

```
Request → Rate Limiter → allowed? → Service
                       → no      → 429 Too Many Requests
```

### Abuse

- Check long URLs against Google Safe Browsing / internal blocklists at creation.
- Re-scan periodically, since a harmless page can turn malicious later.
- CAPTCHA for anonymous creation.
- Allow reporting and takedown of bad links.
- Block creating short links that point to your own domain (redirect chains and loops).

## 26. Security

- **HTTPS everywhere.**
- **Open redirect risk:** attackers use your domain to hide malicious links. Mitigation: scanning, interstitial warning pages for suspicious links, link preview option (`sho.rt/aZ3k9Qp+` shows the destination).
- **Enumeration:** random keys (KGS/random) make guessing hard; with sequential counters, add a reversible shuffle.
- **Input validation:** reject `javascript:`, `data:` and `file:` schemes.
- **Private links:** optional authentication or password for sensitive links.
- **Authentication:** API keys or OAuth for creation APIs.
- **Privacy:** do not log full IPs forever; follow data retention policy.

## 27. Final Production Architecture

```
                          ┌───────────────────┐
                          │      CLIENT       │
                          └─────────┬─────────┘
                                    │
                                    ▼
                          ┌───────────────────┐
                          │  DNS / CDN / WAF  │
                          └─────────┬─────────┘
                                    │
                                    ▼
                          ┌───────────────────┐
                          │  Load Balancer    │
                          └─────────┬─────────┘
                                    │
                                    ▼
                          ┌───────────────────┐
                          │   API Gateway     │
                          │ Auth • Rate Limit │
                          └─────────┬─────────┘
                                    │
              ┌─────────────────────┴─────────────────────┐
              ▼                                           ▼
     ┌─────────────────┐                         ┌─────────────────┐
     │  WRITE SERVICE  │                         │  READ SERVICE   │
     │ (Create / Edit) │                         │   (Redirect)    │
     └────────┬────────┘                         └────────┬────────┘
              │                                           │
      ┌───────┴────────┐                           ┌──────┴───────┐
      ▼                ▼                           ▼              ▼
┌───────────┐   ┌─────────────┐              ┌───────────┐  ┌──────────────┐
│    KGS    │   │  URL DB     │              │   Redis   │  │ URL DB       │
│  (keys)   │   │  (primary)  │ ─replicate─▶ │   Cache   │  │ (replicas)   │
└───────────┘   └─────────────┘              └───────────┘  └──────────────┘
                                                    │
                                                    │ click event (async)
                                                    ▼
                                             ┌─────────────┐
                                             │    Kafka    │
                                             └──────┬──────┘
                                    ┌───────────────┼────────────────┐
                                    ▼               ▼                ▼
                              Stream Job      Raw Event Store   Fraud/Bot
                                    │
                                    ▼
                             Analytics Store ──▶ Analytics API
```

## 28. Java LLD (Concept & Coding style)

This section follows the learning order: **requirements → entities → relationships (UML) → design patterns → code → how the main flows run**. It is plain Java only: no Spring Boot, no controller, no database. A `ConcurrentHashMap` plays the role of the database so you can run everything on your laptop.

### 28.1 Step 1: What must the code do? (requirements for LLD)

- Register a user.
- Create a short URL for a long URL, with an optional custom alias and optional expiry.
- Redirect: give a short key, get the long URL back (or an error if it is unknown, expired or deleted).
- Record every click so we can count clicks per link and per country.
- Delete a short URL (only the owner can).
- Keys must stay unique even when many threads create URLs at the same time.

### 28.2 Step 2: Find the entities (the nouns)

Read the requirements and underline the nouns: *user, short url, click, key range*. These are exactly the four entities listed in section 5. Everything else (service, repository, generator) is behaviour, not data, and comes later.

### 28.3 Step 3: Entities and their attributes

**1. User**

- `userId`: unique id (UUID)
- `name`
- `email`
- `createdAt`

**2. ShortUrl** (the mapping, called UrlMapping in the DB design)

- `shortKey`: the primary key, like `aZ3k9Qp`
- `longUrl`: the original URL
- `userId`: who created it (points to User)
- `createdAt`
- `expiresAt`: `null` means never expires
- `active`: `false` after the owner deletes it
- Behaviour: `isExpired()`, `deactivate()`

**3. ClickEvent**

- `eventId`
- `shortKey`: which link was clicked (points to ShortUrl)
- `clickedAt`
- `country`, `device`, `referrer`

**4. KeyRange** (the KGS-side entity)

- `start`, `end`: the block of numbers this range covers
- `current`: the next number to hand out
- Behaviour: `hasNext()`, `next()`

About **UnusedKey** from section 5: it is the other way to do KGS, where you store a pool of pre-generated random keys (a table with just `key`) and move a key to "used" when handing it out. Both KeyRange and UnusedKey solve the same problem (give every server unique keys). For the code I chose **KeyRange** because it needs no big table and is easier to write and test. Because the generator sits behind the `KeyGenerator` interface, you could add an `UnusedKey`-based generator later without touching the service.

### 28.4 Step 4: UML class diagrams (how everything is related)

The class diagram is split into three pictures so each stays readable. The first shows the data entities and the main service, the second the key generation classes, the third the repository classes. Teal boxes are data entities, purple boxes are behaviour classes and interfaces. The analytics part (`ClickListener` and `AnalyticsService`) is left out of the drawings and is explained in a note after them.

**Diagram 1: entities and the main service.**

Reading the lines: one User creates **many** ShortUrls (`1 — *`), and one ShortUrl receives **many** ClickEvents. The link is just a stored id (`ShortUrl.userId`, `ClickEvent.shortKey`), not an object reference.

The service **creates** ShortUrl and ClickEvent objects (dashed arrows) and **has** three collaborators (solid arrows): `UserService`, a `KeyGenerator` and a `UrlRepository`. The last two are interfaces, expanded in the next diagrams.

**Diagram 2: key generation (Strategy + Factory).**

`RangeKeyGenerator` and `RandomKeyGenerator` both implement `KeyGenerator`, which is the Strategy pattern. `KeyGeneratorFactory` creates the right one from a name. `RangeKeyGenerator` uses a `KeyRangeAllocator` (shared, in real life ZooKeeper or a KGS) to get a `KeyRange` block of numbers, then counts through its own range without asking again until the block runs out.

**Diagram 3: repository (Repository + Decorator).**

Both repositories implement `UrlRepository`. `CachedUrlRepository` also holds another `UrlRepository` inside it (the wraps arrow). That second arrow is what makes it a Decorator: it looks like any repository to the service, but checks its LRU cache before calling the wrapped database repository.

**Analytics (not drawn).** `UrlShortenerService` keeps a list of `ClickListener` interface objects and notifies them with a `ClickEvent` after every redirect. `AnalyticsService` implements `ClickListener`, stores the events and counts clicks. This is the Observer pattern: add the extra box on the right of Diagram 1 with an open arrow from the service and an implements arrow from `AnalyticsService` when you want to show it.

Relationship words used above, in simple terms:

- **implements:** the class promises to provide the interface's methods (`RangeKeyGenerator implements KeyGenerator`).
- **uses / has-a:** the class keeps a reference to another and calls it (`UrlShortenerService` has a `KeyGenerator`).
- **wraps:** a class that holds another object of the same interface and adds behaviour around it (`CachedUrlRepository` wraps a `UrlRepository`).

### 28.5 Step 5: Design patterns used, and where and why

**1. Strategy: `KeyGenerator`.** In section 8 to 14 we saw that a short key can be made in many ways (counter ranges, random characters, hashing), and we may want to switch later. So instead of writing the key logic inside the service, we put it behind a `KeyGenerator` interface with one method, `nextKey()`, and give it two implementations: `RangeKeyGenerator` and `RandomKeyGenerator`. `UrlShortenerService` only calls `keyGenerator.nextKey()` and has no idea how the key was made, so swapping the approach never touches the service code. That is the Strategy pattern: one job, many interchangeable ways of doing it.

**2. Factory: `KeyGeneratorFactory`.** Somebody has to decide which strategy to build, and we do not want `new RangeKeyGenerator(...)` scattered around the code. `KeyGeneratorFactory.create("RANGE", allocator)` takes a name (which could come from a config file) and returns the right generator, so the choice lives in exactly one place and `Main` stays clean.

**3. Repository: `UrlRepository`.** The service needs to save, find and delete short URLs, but it should not care whether they live in a HashMap, MySQL or DynamoDB. So the service talks only to the `UrlRepository` interface (`saveIfAbsent`, `findByKey`, `delete`), and `InMemoryUrlRepository` is today's implementation. When you move to a real database later, you write a new implementation and the service does not change. That is the Repository pattern: hide the storage behind an interface.

**4. Decorator: `CachedUrlRepository`.** Redirects are read far more than anything else, so we want a cache in front of the database, but we do not want to edit the database class or the service to add it. `CachedUrlRepository` implements the same `UrlRepository` interface and holds another `UrlRepository` inside it. On `findByKey` it checks its own LRU map first and calls the wrapped repository only on a miss, and on `delete` it also clears the cache entry. Since it looks just like any other repository, the service cannot tell the difference. That is the Decorator pattern: wrap an object to add behaviour while keeping the same interface.

**5. Observer: `ClickListener`.** When a redirect succeeds, several things might want to know: analytics today, maybe fraud detection or a Kafka publisher tomorrow. If the redirect code called each of them directly, it would keep growing and become tied to all of them. Instead the service keeps a list of `ClickListener` objects (added with `addClickListener`) and, after each redirect, creates one `ClickEvent` and calls `onClick` on every listener. `AnalyticsService` is one listener. New listeners can be plugged in without touching the redirect logic. That is the Observer pattern: the publisher announces, the subscribers react. In production this hand-off would go through Kafka asynchronously, but the idea is the same.

**6. Dependency Injection (a principle, not a GoF pattern).** `UrlShortenerService` never creates its own generator, repository or user service. They are passed in through the constructor, and `Main` is the only place that decides which concrete classes to use (range generator, cached repository, and so on). This is why you can swap pieces or test the service with fake ones without editing it.

**Not used on purpose: Singleton.** The allocator and the service are normally created once in a real app, but we create them once in `Main` and pass them around instead of making them global, which keeps the code easy to test.

### 28.6 Step 6: Code, one piece at a time

All code below was compiled and run together. Put each class in its own file (as in an IDE), or paste them into one file and keep `Main` at the top.

**6.1 Entities**: just data and a few small methods.

```
import java.time.LocalDateTime;

// ===== Entity 1: User =====
class User {
    private final String userId;
    private final String name;
    private final String email;
    private final LocalDateTime createdAt;

    public User(String userId, String name, String email) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.createdAt = LocalDateTime.now();
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

// ===== Entity 2: ShortUrl (the url -> shortKey mapping) =====
class ShortUrl {
    private final String shortKey;          // "aZ3k9Qp"  (this is the primary key)
    private final String longUrl;           // the original url
    private final String userId;            // who created it  (links to User)
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;  // null means "never expires"
    private boolean active;                 // false once the owner deletes it

    public ShortUrl(String shortKey, String longUrl, String userId, LocalDateTime expiresAt) {
        this.shortKey = shortKey;
        this.longUrl = longUrl;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public void deactivate() { this.active = false; }

    public String getShortKey() { return shortKey; }
    public String getLongUrl() { return longUrl; }
    public String getUserId() { return userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
}

// ===== Entity 3: ClickEvent (one row per click, used for analytics) =====
class ClickEvent {
    private final String eventId;
    private final String shortKey;          // which short url was clicked (links to ShortUrl)
    private final LocalDateTime clickedAt;
    private final String country;
    private final String device;
    private final String referrer;

    public ClickEvent(String eventId, String shortKey, String country, String device, String referrer) {
        this.eventId = eventId;
        this.shortKey = shortKey;
        this.clickedAt = LocalDateTime.now();
        this.country = country;
        this.device = device;
        this.referrer = referrer;
    }

    public String getEventId() { return eventId; }
    public String getShortKey() { return shortKey; }
    public LocalDateTime getClickedAt() { return clickedAt; }
    public String getCountry() { return country; }
    public String getDevice() { return device; }
    public String getReferrer() { return referrer; }
}

// ===== Entity 4: KeyRange (a block of numbers one server may use for keys) =====
class KeyRange {
    private final long start;
    private final long end;
    private long current;                   // next number to give out

    public KeyRange(long start, long end) {
        this.start = start;
        this.end = end;
        this.current = start;
    }

    public boolean hasNext() { return current <= end; }

    public long next() {
        return current++;                   // give the number, then move forward
    }

    public long getStart() { return start; }
    public long getEnd() { return end; }
}
```

**6.2 Exceptions**: one small class per error, so the service can say clearly what went wrong.

```
class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String msg) { super(msg); }
}
class AliasAlreadyExistsException extends RuntimeException {
    public AliasAlreadyExistsException(String alias) { super("Alias already taken: " + alias); }
}
class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String key) { super("No url found for key: " + key); }
}
class UrlExpiredException extends RuntimeException {
    public UrlExpiredException(String key) { super("Url expired or deleted: " + key); }
}
```

**6.3 Base62**: turns a number into a short string. It is the same idea as converting to binary, but with 62 symbols (see section 15).

```
class Base62 {
    private static final String CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // number -> text, e.g. 125 -> "21"   (125 = 2*62 + 1)
    public static String encode(long number) {
        if (number == 0) return "0";
        String result = "";
        while (number > 0) {
            result = CHARS.charAt((int) (number % 62)) + result;
            number = number / 62;
        }
        return result;
    }
}
```

**6.4 Key generation**: the Strategy interface, two strategies, the range allocator and the Factory.

```
import java.security.SecureRandom;

// ===== The Strategy interface: "give me the next unique key" =====
interface KeyGenerator {
    String nextKey();
}

// ===== Helper that hands out ranges (in real life: ZooKeeper / a KGS) =====
class KeyRangeAllocator {
    private final long rangeSize;
    private long nextStart = 56_800_235_584L;   // 62^6, so every key is 7 characters long

    public KeyRangeAllocator(long rangeSize) {
        this.rangeSize = rangeSize;
    }

    // synchronized: two servers asking at once must get DIFFERENT ranges
    public synchronized KeyRange allocateRange() {
        KeyRange range = new KeyRange(nextStart, nextStart + rangeSize - 1);
        nextStart = nextStart + rangeSize;
        return range;
    }
}

// ===== Strategy 1: take numbers from our own range, convert to Base62 =====
class RangeKeyGenerator implements KeyGenerator {
    private final KeyRangeAllocator allocator;
    private KeyRange currentRange;

    public RangeKeyGenerator(KeyRangeAllocator allocator) {
        this.allocator = allocator;
    }

    @Override
    public synchronized String nextKey() {
        if (currentRange == null || !currentRange.hasNext()) {
            currentRange = allocator.allocateRange();   // range finished, ask for a new one
        }
        return Base62.encode(currentRange.next());
    }
}

// ===== Strategy 2: random 7 characters (may collide, so the service retries) =====
class RandomKeyGenerator implements KeyGenerator {
    private static final String CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final SecureRandom random = new SecureRandom();

    @Override
    public String nextKey() {
        String key = "";
        for (int i = 0; i < 7; i++) {
            key = key + CHARS.charAt(random.nextInt(62));
        }
        return key;
    }
}

// ===== Factory: decides which strategy to create =====
class KeyGeneratorFactory {
    public static KeyGenerator create(String type, KeyRangeAllocator allocator) {
        if (type.equals("RANGE")) {
            return new RangeKeyGenerator(allocator);
        } else if (type.equals("RANDOM")) {
            return new RandomKeyGenerator();
        }
        throw new IllegalArgumentException("Unknown key generator: " + type);
    }
}
```

How the range idea works: the allocator gives each generator a block of numbers (for example 1000). The generator then counts through its block on its own, with no network call per key, and asks for a new block only when the old one runs out. Two generators can never get the same block because `allocateRange()` is `synchronized`.

**6.5 Repository and the cache Decorator**:

```
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ===== Repository interface: the service only knows THIS, not the database =====
interface UrlRepository {
    boolean saveIfAbsent(ShortUrl url);    // atomic conditional insert: false if key already exists
    ShortUrl findByKey(String shortKey);   // null if not found
    void delete(String shortKey);
}

// ===== "Database" version: a ConcurrentHashMap pretending to be the DB =====
class InMemoryUrlRepository implements UrlRepository {
    private final Map<String, ShortUrl> table = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(ShortUrl url) {
        // putIfAbsent = check + insert as ONE step. Returns null if it saved.
        return table.putIfAbsent(url.getShortKey(), url) == null;
    }

    @Override
    public ShortUrl findByKey(String shortKey) {
        return table.get(shortKey);
    }

    @Override
    public void delete(String shortKey) {
        table.remove(shortKey);
    }
}

// ===== Decorator: same interface, adds an LRU cache in front of any repository =====
class CachedUrlRepository implements UrlRepository {
    private final UrlRepository database;                 // the real repository we wrap
    private final Map<String, ShortUrl> cache;

    public CachedUrlRepository(UrlRepository database, int capacity) {
        this.database = database;
        // accessOrder = true -> the least recently used entry is the "eldest"
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ShortUrl> eldest) {
                return size() > capacity;                 // cache full -> drop the LRU entry
            }
        };
    }

    @Override
    public synchronized boolean saveIfAbsent(ShortUrl url) {
        boolean saved = database.saveIfAbsent(url);
        if (saved) cache.put(url.getShortKey(), url);
        return saved;
    }

    @Override
    public synchronized ShortUrl findByKey(String shortKey) {
        ShortUrl url = cache.get(shortKey);               // 1. try the cache
        if (url != null) return url;                      //    cache HIT
        url = database.findByKey(shortKey);               // 2. cache MISS -> ask the database
        if (url != null) cache.put(shortKey, url);        // 3. remember it for next time
        return url;
    }

    @Override
    public synchronized void delete(String shortKey) {
        database.delete(shortKey);
        cache.remove(shortKey);                           // never leave a stale entry behind
    }
}
```

**6.6 Observer and analytics**:

```
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ===== Observer interface: anyone who wants to know about clicks implements this =====
interface ClickListener {
    void onClick(ClickEvent event);
}

// ===== One observer: stores events and counts clicks =====
class AnalyticsService implements ClickListener {
    private final Map<String, List<ClickEvent>> eventsByKey = new ConcurrentHashMap<>();

    @Override
    public void onClick(ClickEvent event) {
        eventsByKey
            .computeIfAbsent(event.getShortKey(), k -> Collections.synchronizedList(new ArrayList<>()))
            .add(event);
    }

    public int getClickCount(String shortKey) {
        List<ClickEvent> events = eventsByKey.get(shortKey);
        return events == null ? 0 : events.size();
    }

    public int getClickCountByCountry(String shortKey, String country) {
        List<ClickEvent> events = eventsByKey.get(shortKey);
        if (events == null) return 0;
        int count = 0;
        synchronized (events) {
            for (ClickEvent e : events) {
                if (e.getCountry().equals(country)) count++;
            }
        }
        return count;
    }
}
```

**6.7 Services**: user service, validator and the main `UrlShortenerService`.

```
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ===== UserService: create and find users =====
class UserService {
    private final Map<String, User> users = new ConcurrentHashMap<>();

    public User registerUser(String name, String email) {
        User user = new User(UUID.randomUUID().toString(), name, email);
        users.put(user.getUserId(), user);
        return user;
    }

    public User getUser(String userId) {
        User user = users.get(userId);
        if (user == null) throw new IllegalArgumentException("Unknown user: " + userId);
        return user;
    }
}

// ===== Validation in one small place =====
class UrlValidator {
    public static void validate(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) {
            throw new InvalidUrlException("Url is empty");
        }
        if (!(longUrl.startsWith("http://") || longUrl.startsWith("https://"))) {
            throw new InvalidUrlException("Only http/https urls allowed");
        }
        if (longUrl.length() > 2048) {
            throw new InvalidUrlException("Url too long");
        }
    }
}

// ===== The main service: shorten + redirect (+ delete) =====
class UrlShortenerService {
    private static final String DOMAIN = "https://sho.rt/";
    private static final int MAX_RETRIES = 5;

    private final KeyGenerator keyGenerator;                         // Strategy
    private final UrlRepository urlRepository;                       // Repository (maybe Decorated)
    private final UserService userService;
    private final List<ClickListener> listeners = new ArrayList<>(); // Observers

    // Dependency Injection: everything comes in through the constructor
    public UrlShortenerService(KeyGenerator keyGenerator, UrlRepository urlRepository,
                               UserService userService) {
        this.keyGenerator = keyGenerator;
        this.urlRepository = urlRepository;
        this.userService = userService;
    }

    public void addClickListener(ClickListener listener) {
        listeners.add(listener);
    }

    // ---------- FEATURE 1: create a short url ----------
    public String shorten(String userId, String longUrl, String customAlias, LocalDateTime expiresAt) {
        userService.getUser(userId);          // 1. user must exist
        UrlValidator.validate(longUrl);       // 2. url must be valid

        if (customAlias != null) {            // 3a. user chose their own key
            ShortUrl url = new ShortUrl(customAlias, longUrl, userId, expiresAt);
            if (!urlRepository.saveIfAbsent(url)) {
                throw new AliasAlreadyExistsException(customAlias);
            }
            return DOMAIN + customAlias;
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {   // 3b. we generate the key
            String key = keyGenerator.nextKey();
            ShortUrl url = new ShortUrl(key, longUrl, userId, expiresAt);
            if (urlRepository.saveIfAbsent(url)) {
                return DOMAIN + key;          // saved, the key is ours
            }
            // key was taken (can happen with random / alias clash) -> try again
        }
        throw new IllegalStateException("Could not generate a unique key");
    }

    // ---------- FEATURE 2: redirect ----------
    public String redirect(String shortKey, String country, String device, String referrer) {
        ShortUrl url = urlRepository.findByKey(shortKey);   // 1. find it (cache first, then DB)
        if (url == null) {
            throw new UrlNotFoundException(shortKey);       // 2. unknown key -> 404
        }
        if (!url.isActive() || url.isExpired()) {
            throw new UrlExpiredException(shortKey);        // 3. deleted or expired -> 410
        }

        ClickEvent event = new ClickEvent(UUID.randomUUID().toString(), shortKey,
                                          country, device, referrer);
        for (ClickListener listener : listeners) {          // 4. tell every observer
            listener.onClick(event);
        }
        return url.getLongUrl();                            // 5. caller sends the HTTP 302
    }

    // ---------- FEATURE 3: delete (only the owner) ----------
    public void deleteUrl(String userId, String shortKey) {
        ShortUrl url = urlRepository.findByKey(shortKey);
        if (url == null) throw new UrlNotFoundException(shortKey);
        if (!url.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Only the owner can delete this url");
        }
        url.deactivate();
        urlRepository.delete(shortKey);
    }
}
```

**6.8 Main**: wires everything together and tests every feature.

```
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) throws Exception {

        // ---- Wire everything together (this is where the patterns plug in) ----
        KeyRangeAllocator allocator = new KeyRangeAllocator(1000);
        KeyGenerator keyGenerator = KeyGeneratorFactory.create("RANGE", allocator);   // Factory + Strategy
        UrlRepository repository = new CachedUrlRepository(new InMemoryUrlRepository(), 100); // Decorator
        UserService userService = new UserService();
        UrlShortenerService service = new UrlShortenerService(keyGenerator, repository, userService);

        AnalyticsService analytics = new AnalyticsService();
        service.addClickListener(analytics);                                          // Observer

        // ---- Test 1: create a user and a short url ----
        User komal = userService.registerUser("Komal", "komal@example.com");
        String shortUrl = service.shorten(komal.getUserId(),
                "https://www.example.com/products/electronics/phones?id=98213", null, null);
        System.out.println("1. Short url      : " + shortUrl);

        // ---- Test 2: redirect (also fires the click event) ----
        String key = shortUrl.substring(shortUrl.lastIndexOf('/') + 1);
        System.out.println("2. Redirects to   : " + service.redirect(key, "IN", "mobile", "google.com"));
        service.redirect(key, "IN", "desktop", "twitter.com");
        service.redirect(key, "US", "mobile", "direct");
        System.out.println("   Total clicks   : " + analytics.getClickCount(key));
        System.out.println("   Clicks from IN : " + analytics.getClickCountByCountry(key, "IN"));

        // ---- Test 3: custom alias, and a clash ----
        System.out.println("3. Custom alias   : " + service.shorten(komal.getUserId(),
                "https://example.com/sale", "my-sale", null));
        try {
            service.shorten(komal.getUserId(), "https://example.com/other", "my-sale", null);
        } catch (AliasAlreadyExistsException e) {
            System.out.println("   Second user     : " + e.getMessage());
        }

        // ---- Test 4: expired url ----
        String expiredUrl = service.shorten(komal.getUserId(), "https://example.com/old",
                "old-link", LocalDateTime.now().minusSeconds(1));
        try {
            service.redirect("old-link", "IN", "mobile", "direct");
        } catch (UrlExpiredException e) {
            System.out.println("4. Expired        : " + e.getMessage());
        }

        // ---- Test 5: unknown key and invalid url ----
        try {
            service.redirect("nope123", "IN", "mobile", "direct");
        } catch (UrlNotFoundException e) {
            System.out.println("5. Not found      : " + e.getMessage());
        }
        try {
            service.shorten(komal.getUserId(), "ftp://bad.com", null, null);
        } catch (InvalidUrlException e) {
            System.out.println("   Invalid url    : " + e.getMessage());
        }

        // ---- Test 6: delete (only the owner can) ----
        User other = userService.registerUser("Other", "other@example.com");
        try {
            service.deleteUrl(other.getUserId(), key);
        } catch (IllegalArgumentException e) {
            System.out.println("6. Delete by other: " + e.getMessage());
        }
        service.deleteUrl(komal.getUserId(), key);
        try {
            service.redirect(key, "IN", "mobile", "direct");
        } catch (UrlNotFoundException e) {
            System.out.println("   After delete   : " + e.getMessage());
        }

        // ---- Test 7: 20 threads x 500 urls at the same time, all keys must be unique ----
        Set<String> allKeys = ConcurrentHashMap.newKeySet();
        Thread[] threads = new Thread[20];
        for (int t = 0; t < 20; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    allKeys.add(service.shorten(komal.getUserId(), "https://example.com/x", null, null));
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        System.out.println("7. Keys created   : " + allKeys.size() + " (expected 10000, no duplicates)");

        // ---- Test 8: 50 threads fight for the same alias, exactly one must win ----
        AtomicInteger winners = new AtomicInteger();
        Thread[] racers = new Thread[50];
        for (int t = 0; t < 50; t++) {
            racers[t] = new Thread(() -> {
                try {
                    service.shorten(komal.getUserId(), "https://example.com/race", "race", null);
                    winners.incrementAndGet();
                } catch (AliasAlreadyExistsException e) { /* lost the race, fine */ }
            });
            racers[t].start();
        }
        for (Thread t : racers) t.join();
        System.out.println("8. Alias winners  : " + winners.get() + " (expected 1)");
    }
}
```

Output when run:

```
1. Short url      : https://sho.rt/1000000
2. Redirects to   : https://www.example.com/products/electronics/phones?id=98213
   Total clicks   : 3
   Clicks from IN : 2
3. Custom alias   : https://sho.rt/my-sale
   Second user     : Alias already taken: my-sale
4. Expired        : Url expired or deleted: old-link
5. Not found      : No url found for key: nope123
   Invalid url    : Only http/https urls allowed
6. Delete by other: Only the owner can delete this url
   After delete   : No url found for key: 1000000
7. Keys created   : 10000 (expected 10000, no duplicates)
8. Alias winners  : 1 (expected 1)
```

Tests 7 and 8 matter most: 20 threads creating 500 URLs each produced 10,000 unique keys, and 50 threads fighting for one alias produced exactly one winner.

### 28.7 Step 7: How the main services work (flow of each feature)

**Shorten flow** (`shorten(userId, longUrl, customAlias, expiresAt)`):

```
1. userService.getUser(userId)              -> user must exist
2. UrlValidator.validate(longUrl)           -> must be a valid http/https url
3. customAlias given?
     YES -> build ShortUrl(alias) -> repository.saveIfAbsent()
              false -> throw AliasAlreadyExistsException
              true  -> return sho.rt/alias
     NO  -> loop up to 5 times:
              key = keyGenerator.nextKey()          (Strategy)
              repository.saveIfAbsent(ShortUrl)     (atomic conditional insert)
              true  -> return sho.rt/key
              false -> key was taken, try next key
```

**Redirect flow** (`redirect(shortKey, country, device, referrer)`):

```
1. repository.findByKey(shortKey)           -> CachedUrlRepository checks cache, then DB
2. not found?          -> UrlNotFoundException   (HTTP 404)
3. deleted or expired? -> UrlExpiredException    (HTTP 410)
4. build ClickEvent, call listener.onClick() for every listener   (Observer)
5. return longUrl      -> the caller sends HTTP 302 with this url
```

**Delete flow** (`deleteUrl(userId, shortKey)`): find the URL, check that `url.getUserId()` equals the caller, then `deactivate()` and remove it from the repository (the cache entry is removed too, so a deleted link cannot be served from a stale cache).

Where the thread-safety comes from: `putIfAbsent` in the repository (no two users get the same key or alias), `synchronized` in the allocator and generator (no two threads get the same number), and `computeIfAbsent` with a synchronized list in analytics (no lost click events).

### 28.8 What to say in the interview about the LLD

> "I found the entities from the requirements: User, ShortUrl, ClickEvent and KeyRange. The main class is UrlShortenerService, which depends only on interfaces. Key generation is a Strategy, so I can swap range-based, random or hash-based generation. Storage is behind a Repository interface, and I add caching with a Decorator, so the service does not change. Clicks use an Observer, so analytics can be added or moved to Kafka without touching the redirect code. Uniqueness comes from an atomic conditional insert, and the concurrent test with 20 threads confirms there are no duplicate keys."

## 29. Concurrency Practice

Exercise 1: **Unique keys under concurrency**

```
Create 100 threads
Each calls RangeKeyGenerator.nextKey() 1000 times
Collect all keys into a ConcurrentHashMap.newKeySet()

Expected: 100,000 keys, zero duplicates
```

Tests that one generator hands out unique keys even when many threads call it at once.

### Exercise 1 in detail: how simultaneous requests are handled

**What the exercise really simulates.** As written, the 100 threads live in **one JVM**, so it models **one server node** receiving 100 requests at the same instant (a web server handles each request on its own thread). The danger is that two threads call `nextKey()` at the same moment and both receive the same number. The fix is `synchronized` on `nextKey()`: only one thread at a time can enter, so the others wait a few nanoseconds and then take the next number.

**Why a missing lock gives duplicates.** `counter++` looks like one step but is really three: read the value, add one, write it back. If thread 1 and thread 2 both read `5` before either writes, both return key 5 and both write 6. In the broken version in the code below, I added `Thread.yield()` between the read and the write to make the overlap happen on purpose; with that, 100,000 requests produced only about 1,000 unique keys. (Without the yield the bug is rare and hard to reproduce, which is exactly why these bugs reach production.)

**What changes with multiple server nodes.** `synchronized` (and `AtomicLong`) only work **inside one JVM**. Node 1 and node 2 are separate processes on separate machines, with no shared memory, so a Java lock on node 1 cannot stop node 2. If both nodes started counting from 1 on their own, they would hand out the same keys. We solve this with **two layers**:

- **Layer 1, across nodes: ranges.** A shared service (ZooKeeper, a KGS, or a DB row updated atomically) gives each node a **different block of numbers**, for example node 1 gets 1 to 1000, node 2 gets 1001 to 2000. Because the blocks never overlap, keys from different nodes can never collide, and the nodes do not need to talk to each other per request. The shared allocator is the only place that needs strong coordination, and it is hit once per 1000 keys, not once per request. In code this is `allocateRange()`, which is `synchronized` here and would be an atomic increment or a lock in ZooKeeper in real life.
- **Layer 2, inside a node: a lock.** Inside each node, many request threads share that node's own range, so `nextKey()` is `synchronized` (or uses `AtomicLong`) to make sure two threads never take the same number from the same block.

```
            Shared allocator (ZooKeeper / KGS)
            gives out blocks that never overlap
              |             |             |
      +-------+      +------+      +------+
      v              v             v
  Node 1          Node 2        Node 3
  range 1-1000    1001-2000     2001-3000
  20 threads      20 threads    20 threads
  (lock inside)   (lock inside) (lock inside)
      |              |             |
      +--- keys: 1,2,3...   1001,1002...   2001,2002... ---+  all unique
```

**What happens when requests arrive at the same instant on different nodes.** The load balancer sends request A to node 1 and request B to node 2 at the same time. Node 1 takes the next number from its own block (say 7) and node 2 takes the next number from its block (say 1003). They do not wait for each other and they cannot clash, because the blocks are disjoint. If node 1's block runs out, it asks the allocator for a new block; if two nodes ask at the same moment, the allocator serves them one after the other (that is the `synchronized`), so they get different blocks.

**What if a node crashes?** The unused numbers in its block are lost. That is fine: the key space is trillions, and losing a few hundred numbers never causes a duplicate. If the allocator itself is down, nodes keep working until their current block is used up, which is why a block size of 1000 or more gives you breathing room.

The tested code below runs three parts: (A) the exercise as written, one node and 100 threads; (B) the broken version without a lock; (C) the multi-node simulation with 5 nodes, each with its own generator and 20 request threads, all sharing one allocator. Nodes are simulated as objects in one JVM, since a real multi-machine setup would behave the same way through the allocator.

```
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Exercise1 {

    // ---- the real classes, trimmed to what this exercise needs ----
    static class KeyRange {
        private final long end;
        private long current;
        KeyRange(long start, long end) { this.current = start; this.end = end; }
        boolean hasNext() { return current <= end; }
        long next() { return current++; }
    }

    // Plays the role of ZooKeeper / KGS: the ONE shared place all nodes ask for ranges
    static class KeyRangeAllocator {
        private final long rangeSize;
        private long nextStart = 56_800_235_584L;
        KeyRangeAllocator(long rangeSize) { this.rangeSize = rangeSize; }
        synchronized KeyRange allocateRange() {
            KeyRange r = new KeyRange(nextStart, nextStart + rangeSize - 1);
            nextStart += rangeSize;
            return r;
        }
    }

    // One of these lives INSIDE each server node
    static class RangeKeyGenerator {
        private final KeyRangeAllocator allocator;
        private KeyRange currentRange;
        RangeKeyGenerator(KeyRangeAllocator a) { this.allocator = a; }
        synchronized String nextKey() {                       // protects threads inside ONE node
            if (currentRange == null || !currentRange.hasNext()) {
                currentRange = allocator.allocateRange();     // protects ACROSS nodes
            }
            return "K" + currentRange.next();
        }
    }

    // The BROKEN version: no synchronized, so two threads can read the same number
    static class BrokenGenerator {
        private long counter = 0;
        String nextKey() {
            long n = counter;        // 1. read the number
            Thread.yield();          //    (pretend another thread gets to run right here)
            counter = n + 1;         // 2. write number + 1  -> two threads may both have read n
            return "K" + n;
        }
    }

    public static void main(String[] args) throws Exception {
        // ===== Part A: ONE node, 100 threads x 1000 keys (this is Exercise 1 as written) =====
        KeyRangeAllocator allocatorA = new KeyRangeAllocator(1000);
        RangeKeyGenerator node = new RangeKeyGenerator(allocatorA);
        Set<String> keysA = ConcurrentHashMap.newKeySet();
        runThreads(100, 1000, () -> keysA.add(node.nextKey()));
        System.out.println("A) one node, 100 threads      : " + keysA.size() + " unique of 100000");

        // ===== Part B: the same test with the broken (unsynchronized) generator =====
        BrokenGenerator broken = new BrokenGenerator();
        Set<String> keysB = ConcurrentHashMap.newKeySet();
        runThreads(100, 1000, () -> keysB.add(broken.nextKey()));
        System.out.println("B) broken generator           : " + keysB.size() + " unique of 100000 (duplicates = bug)");

        // ===== Part C: 5 server nodes, each with its OWN generator, one shared allocator =====
        KeyRangeAllocator sharedAllocator = new KeyRangeAllocator(1000);
        Set<String> keysC = ConcurrentHashMap.newKeySet();
        Thread[] all = new Thread[5 * 20];
        for (int node_i = 0; node_i < 5; node_i++) {
            RangeKeyGenerator nodeGen = new RangeKeyGenerator(sharedAllocator);   // node's own generator
            for (int t = 0; t < 20; t++) {                                        // 20 request threads per node
                Thread th = new Thread(() -> {
                    for (int i = 0; i < 1000; i++) keysC.add(nodeGen.nextKey());
                });
                all[node_i * 20 + t] = th;
            }
        }
        for (Thread th : all) th.start();
        for (Thread th : all) th.join();
        System.out.println("C) 5 nodes x 20 threads each  : " + keysC.size() + " unique of 100000");
    }

    // Starts all threads at the SAME moment (latch) so requests really overlap
    static void runThreads(int threads, int perThread, Runnable work) throws Exception {
        java.util.concurrent.CountDownLatch startSignal = new java.util.concurrent.CountDownLatch(1);
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                try { startSignal.await(); } catch (InterruptedException e) { return; }
                for (int j = 0; j < perThread; j++) work.run();
            });
            ts[i].start();
        }
        startSignal.countDown();          // GO!
        for (Thread t : ts) t.join();
    }
}
```

Output when run:

```
A) one node, 100 threads      : 100000 unique of 100000
B) broken generator           : 1039 unique of 100000 (duplicates = bug)
C) 5 nodes x 20 threads each  : 100000 unique of 100000
```

The number in B varies from run to run; A and C are always 100,000. Interview line: "Within a node I use a lock or an atomic counter. Across nodes I never share a counter; I give each node its own non-overlapping range from a coordinator, so uniqueness holds without per-request coordination."

Exercise 2: **Custom alias race**

```
50 threads all try to create alias "my-sale" with different long URLs
Expected: exactly 1 success, 49 AliasAlreadyExistsException
```

Tests `saveIfAbsent` atomicity (use `ConcurrentHashMap.putIfAbsent` in the in-memory version).

Exercise 3: **Cache stampede**

```
1000 threads request the same expired hot key at once
Expected: exactly 1 DB call, others wait for that result
```

Practice with `ConcurrentHashMap.computeIfAbsent` or a per-key lock.

## 30. Spring Boot Structure

```
url-shortener/
│
├── controller/
│   ├── UrlController
│   └── AnalyticsController
│
├── service/
│   ├── UrlShortenerService
│   ├── KeyGenerationService
│   ├── AnalyticsService
│   └── RateLimiterService
│
├── repository/
│   ├── UrlRepository
│   └── ClickEventRepository
│
├── keygen/
│   ├── KeyGenerator
│   ├── RangeKeyGenerator
│   ├── KgsKeyGenerator
│   └── Base62
│
├── cache/
│   ├── UrlCache
│   └── RedisUrlCache
│
├── messaging/
│   └── ClickEventPublisher (Kafka)
│
├── entity/
├── dto/
├── exception/
└── config/
```

## 31. SOLID / Design Patterns

- **Strategy:** `KeyGenerator` with multiple implementations (swap hash, random, counter, KGS without touching service code).
- **Repository:** `UrlRepository` hides SQL/NoSQL.
- **Decorator / Proxy:** caching layer wrapping the repository.
- **Observer / Event-driven:** click events published to listeners.
- **Factory:** create the right `KeyGenerator` from config.
- **Singleton:** the generator/range holder per JVM.
- **Dependency Inversion:** service depends on interfaces, not Redis or MySQL classes.
- **Single Responsibility:** key generation, validation, storage, caching and analytics are separate classes.

## 32. Failure Scenarios

### Case 1: Two users get the same key

Prevented by design: unique key source (KGS / ranges) plus a DB `PRIMARY KEY` as the final safety net. A unique-violation triggers retry.

### Case 2: Two users want the same custom alias

Atomic conditional insert. One wins, the other gets `409`.

### Case 3: Redis is down

Redirects fall back to the DB (slower, but working). Protect the DB with rate limiting, circuit breakers and a small local in-process cache. Redis is an optimization, **not** the source of truth.

### Case 4: Database primary fails

Promote a replica to primary (automatic failover). Reads continue from the other replicas meanwhile. Writes are briefly unavailable, which is acceptable because redirect matters more than creation.

### Case 5: KGS is down

App servers still have buffered keys in memory, so creation continues for a while. Run KGS in primary/standby mode. Fallback: switch to random key generation with collision retry.

### Case 6: App server crashes holding unused keys

Those keys are lost. Acceptable given a keyspace of trillions.

### Case 7: Kafka is down

Redirect must still work. Buffer events locally or drop them. Analytics may lose some events, but redirects are never blocked.

### Case 8: Viral link / hot key

CDN or local cache absorbs it. Replicate the hot key across cache nodes. Scale read service horizontally.

### Case 9: Cache stampede on a popular key

Single-flight loading, jittered TTL, and refresh-ahead for hot keys.

### Case 10: Data center / region failure

Multi-region deployment with global load balancing. Replicate the URL DB across regions (async replication is fine since the data is mostly immutable once written).

### Case 11: Malicious URL created

Blocklist check at creation, periodic rescans, takedown API, interstitial warning page.

## 33. Advanced (Interview Bonus) Topics

### 33.1 Bloom filter for non-existent keys

Put a Bloom filter of all existing keys in front of the cache/DB. If the filter says "definitely not present", return 404 without touching the DB. Protects against random-key scanning attacks. Accept a small false-positive rate (they just do a normal lookup).

### 33.2 Bit-shuffling counters

If using counters but you want non-guessable keys, pass the counter through a reversible permutation (e.g., Feistel network or XOR with a secret plus bit-rotation) before Base62. You keep uniqueness (it is a bijection) and lose sequential order.

### 33.3 Multi-region and geo-routing

Anycast/GeoDNS sends users to the nearest region. Each region has a read cache and replicas. Writes can go to a home region, or each region owns its own key range or prefix so writes never conflict.

### 33.4 Link preview and deduplication

Keep a hash index on the long URL for the same user (`hash(userId + longUrl)`) to return an existing short URL instead of creating duplicates.

### 33.5 Soft delete and audit

Use `is_active = false` rather than hard delete, so you can investigate abuse and reserve keys. Invalidate the cache immediately.

### 33.6 Analytics accuracy

Bots and prefetchers inflate counts. Filter by user-agent, dedupe by (ip hash, key, time window), and separate "unique clicks" from "total clicks".

## 34. ⭐ Interview Follow-Up Questions

### Requirements

1. What are the functional requirements?
2. What are the non-functional requirements?
3. What is the read:write ratio and why does it matter?
4. What is more important here: consistency or availability?
5. Should the same long URL always map to the same short URL?

### Estimation

6. How many URLs will you store in 5 years?
7. How much storage do you need?
8. How many characters should the key be and why?
9. How much cache memory do you need?
10. What is the QPS for reads and writes?

### Key Generation

11. How will you generate a unique short key?
12. What are the problems with hashing and truncating?
13. What is the collision probability for random keys?
14. Why Base62 and not Base64?
15. How does a Key Generation Service work?
16. What happens if KGS goes down?
17. What if an app server crashes with unused keys?
18. How do you avoid two servers getting the same key?
19. How does the counter approach scale across servers?
20. How does ZooKeeper range allocation work?
21. How would you make sequential keys unguessable?

### Database

22. SQL or NoSQL and why?
23. What is the primary key?
24. What indexes will you create?
25. How will you shard the database?
26. Why not shard by long URL or by user id?
27. What is consistent hashing and why use it?
28. How do you handle replica lag?

### Redirect

29. 301 or 302, and why?
30. How do you make redirect fast?
31. What do you return for expired or deleted links?
32. How do you count clicks without slowing redirect?

### Cache

33. What caching strategy will you use?
34. Which eviction policy and why?
35. What is cache stampede and how do you prevent it?
36. What is cache penetration and how do you prevent it?
37. How do you handle a viral hot key?
38. What happens if Redis crashes?

### Features

39. How do you implement custom aliases safely?
40. How do you implement expiry?
41. Should expired keys be reused?
42. How do you delete a URL and clear the cache?

### Analytics

43. Why Kafka for click events?
44. How do you avoid a hot row for click counters?
45. How do you filter bots?

### Security and Abuse

46. How do you prevent abuse and spam?
47. How do you prevent malicious URLs?
48. How do you prevent enumeration of all links?
49. How does rate limiting work?

### Scaling and Reliability

50. How do you scale the read service?
51. How do you scale the write service?
52. How do you handle a region failure?
53. What would you monitor? (redirect p99 latency, error rate, cache hit ratio, DB load, KGS unused key count, Kafka lag)

## 35. 🔥 Extra Topics: Separate Study List

### Must Know

```
⭐ Hashing (MD5, SHA, MurmurHash) and collisions
⭐ Base62 / Base64 encoding
⭐ HTTP status codes (301, 302, 404, 410, 429)
⭐ Caching (cache-aside, LRU, TTL)
⭐ Redis
⭐ Database indexing
⭐ Replication
⭐ Sharding
⭐ Consistent hashing
⭐ Load balancer
⭐ API Gateway
⭐ Rate limiting (token bucket, sliding window)
⭐ Idempotency
```

### Advanced

```
⭐ Bloom filter
⭐ Snowflake ID
⭐ ZooKeeper / etcd
⭐ Kafka
⭐ CDN
⭐ CQRS
⭐ Cassandra / DynamoDB
⭐ Stream processing (Flink / Spark)
⭐ Multi-region architecture
⭐ Cache stampede / hot key handling
```

## 36. 🎯 Java Practice Roadmap

```
Phase 1: Pure Java
  Base62 encode/decode, UrlMapping class, in-memory HashMap shortener

Phase 2: LLD
  KeyGenerator interface + 3 strategies, Repository, Service (SOLID, Strategy pattern)

Phase 3: Multithreading
  AtomicLong, ConcurrentHashMap.putIfAbsent, 100-thread uniqueness test,
  in-memory token bucket rate limiter

Phase 4: Spring Boot
  REST APIs, JPA/Hibernate, PostgreSQL (unique constraint), exception handling

Phase 5: Redis
  Cache-aside, TTL, negative caching, rate limiter with Redis + Lua

Phase 6: Kafka
  Producer for click events, consumer for aggregation, partition by shortKey

Phase 7: Scaling
  Docker, run 3 app instances behind Nginx, load test with k6 or JMeter

Phase 8: Advanced
  Bloom filter, KGS service, sharding simulation, Kubernetes, monitoring
```

## 37. 🧠 Final 30-Second Interview Explanation

> "A URL shortener is a read-heavy system, roughly 100 reads for every write, so I would split it into a write service and a read service behind a load balancer and API gateway. For key generation I would use a Key Generation Service that pre-generates unique random 7-character Base62 keys, so app servers can grab batches and never need to check for collisions on the hot path. A 7-character Base62 key gives about 3.5 trillion combinations, far more than the roughly 6 billion URLs we expect in 5 years. The mapping is stored in a key-value or sharded SQL database with `short_key` as the primary key, with custom aliases handled by an atomic conditional insert. Redirects go through a Redis cache using cache-aside with LRU eviction, backed by read replicas, and return a 302 so we can track clicks and enforce expiry. Click events are published asynchronously to Kafka so analytics never slow down the redirect. We protect the system with rate limiting, URL validation and malware checks, and for reliability we run replicas, standby KGS, multi-region deployment, and fall back to the database if Redis is down."

### ⭐ The 5 things you absolutely must understand

```
1. WHY read-heavy?          → 100:1 ratio drives caching, replicas, split read/write paths
2. WHY KGS / counters?      → Collision-free unique keys without DB checks per request
3. WHY Base62, 7 chars?     → URL-safe, 3.5 trillion combinations
4. WHY 302 over 301?        → Analytics, expiry and editing need every click to hit us
5. WHY Kafka for clicks?    → Redirect never waits on analytics; absorbs spikes
```

**Most important:** The database (with a primary-key uniqueness guarantee) is the source of truth, the cache (Redis) is purely a speed optimization, and key generation is separated from request handling so uniqueness is guaranteed by design instead of being checked after the fact. That separation is what makes the design fast, scalable and easy to explain in an interview.