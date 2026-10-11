package Services;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import Entities.Payment;
import Entities.PaymentStatus;

public class PaymentGateway {
	private final Map<String, Payment> paymentsByIdempotencyKey = new ConcurrentHashMap<>();
	private final AtomicInteger realCharges = new AtomicInteger();
	private volatile boolean shouldFail;

	public int getRealCharges() {
		return realCharges.get();
	}

	public void setShouldFail(boolean shouldFail) {
		this.shouldFail = shouldFail;
	}

	public Payment charge(String bookingId, String idempotencyKey, double amount) {
		requireText(bookingId, "bookingId");
		requireText(idempotencyKey, "idempotencyKey");
		if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
			throw new IllegalArgumentException("amount must be a finite, non-negative value");
		}

		PaymentStatus status = shouldFail ? PaymentStatus.FAILED : PaymentStatus.SUCCESS;
		Payment newPayment = new Payment(UUID.randomUUID().toString(), bookingId, amount,
										 status, idempotencyKey);
		Payment existing = paymentsByIdempotencyKey.putIfAbsent(idempotencyKey, newPayment);
		Payment payment = existing == null ? newPayment : existing;

		if (!payment.getBookingId().equals(bookingId)
				|| Double.compare(payment.getAmount(), amount) != 0) {
			throw new IllegalArgumentException("idempotencyKey was already used for a different charge");
		}
		if (existing == null && status == PaymentStatus.SUCCESS) realCharges.incrementAndGet();
		return payment;
	}

	public void refund(String idempotencyKey) {
		requireText(idempotencyKey, "idempotencyKey");
		paymentsByIdempotencyKey.compute(idempotencyKey, (key, payment) -> {
			if (payment == null) {
				throw new NoSuchElementException("No payment found for idempotency key: " + key);
			}
			if (payment.getStatus() == PaymentStatus.REFUNDED) return payment;
			if (payment.getStatus() != PaymentStatus.SUCCESS) {
				throw new IllegalStateException("Only successful payments can be refunded");
			}
			return new Payment(payment.getPaymentId(), payment.getBookingId(), payment.getAmount(),
							   PaymentStatus.REFUNDED, payment.getIdempotencyKey());
		});
	}

	private void requireText(String value, String name) {
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalArgumentException(name + " must not be null or blank");
		}
	}
}
