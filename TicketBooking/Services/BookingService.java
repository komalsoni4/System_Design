package Services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import Entities.Booking;
import Entities.BookingStatus;
import Entities.Payment;
import Entities.PaymentStatus;
import Services.BookingRepository;
import Services.SeatRepository;

public class BookingService{
    private final SeatRepository seatRepository;          // Repository
    private final BookingRepository bookingRepository;    // Repository
    private final PaymentGateway paymentGateway;          // Strategy
    private final long holdMillis;                        // how long a hold lasts
    //private final List<BookingListener> listeners = new ArrayList<>();   // Observers
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

    // public void addListener(BookingListener listener) { listeners.add(listener); }

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
                throw new IllegalStateException("Seat is unavailable: " + seatNo);
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
                throw new IllegalStateException("Booking hold has expired: " + bookingId);
            }

            Payment payment = paymentGateway.charge(bookingId, idempotencyKey, booking.getTotalAmount());
            if (payment.getStatus() != PaymentStatus.SUCCESS) {
                releaseSeats(booking);                     // free the seats for others
                booking.setStatus(BookingStatus.CANCELLED);
                throw new IllegalStateException("Payment failed for booking: " + bookingId);
            }

            // Payment worked. Turn every HELD seat into BOOKED.
            for (String seatNo : booking.getSeatNos()) {
                boolean ok = seatRepository.confirm(booking.getShowId(), seatNo, bookingId,
                                                    System.currentTimeMillis());
                if (!ok) {                                 // hold was lost while we were paying
                    paymentGateway.refund(idempotencyKey); // give the money back
                    releaseSeats(booking);
                    booking.setStatus(BookingStatus.EXPIRED);
                    throw new IllegalStateException("Booking hold has expired: " + bookingId);
                }
            }

            booking.setPayment(payment);
            booking.setStatus(BookingStatus.CONFIRMED);
            processedKeys.put(idempotencyKey, bookingId);
           // for (BookingListener listener : listeners) listener.onConfirmed(booking);
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
            //for (BookingListener listener : listeners) listener.onCancelled(booking);
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