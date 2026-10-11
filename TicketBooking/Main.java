import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import Entities.Booking;
import Entities.SeatStatus;
import Entities.ShowSeat;
import Services.BookingService;
import Services.InMemoryBookingRepository;
import Services.InMemorySeatRepository;
import Services.PaymentGateway;
import Services.SeatRepository;

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
        for (String s : seatNos) repo.addSeat(new ShowSeat(s, showId, 250.0));
        return repo;
    }

    static void expect(String label, Runnable action) {
        try { action.run(); System.out.println(label + " -> no error (unexpected)"); }
        catch (RuntimeException e) { System.out.println(label + " -> " + e.getMessage()); }
    }

    public static void main(String[] args) throws Exception {
        SeatRepository seats = newSeats("show1", "A1", "A2", "A3", "A4", "B1");
        PaymentGateway gateway = new PaymentGateway();
        BookingService service = new BookingService(seats, new InMemoryBookingRepository(), gateway, 300);
        //NotificationService notifications = new NotificationService();
        //service.addListener(notifications);

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
                                          new PaymentGateway(), 60_000);
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
            } catch (IllegalStateException e) {
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
