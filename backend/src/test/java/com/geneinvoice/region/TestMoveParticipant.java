package com.geneinvoice.region;

import com.geneinvoice.common.BadRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * A region move participant the tests steer: it can refuse the next move, fail it after everything is written, or
 * report rows of its own, and it remembers what it was told. A bean of {@code IntegrationTestBase.TestBeans}, reset
 * before every test.
 */
public class TestMoveParticipant implements RegionMoveParticipant {

    /** One move as a participant hears of it: the customer, from and to. */
    public record Call(Long customerId, Long fromRegionId, Long toRegionId) {}

    private String veto;
    private String failAfter;
    private Runnable whileMoving;
    private int rows;
    private final List<Call> before = new ArrayList<>();
    private final List<Call> after = new ArrayList<>();

    /** Every move refuses with this message until {@link #reset()}. */
    public void veto(String message) {
        this.veto = message;
    }

    /**
     * Every move fails with this message once it has written everything, when this participant is told of it,
     * until {@link #reset()}. {@code whileMoving} runs first, inside the move's transaction; may be null.
     */
    public void failAfter(String message, Runnable whileMoving) {
        this.failAfter = message;
        this.whileMoving = whileMoving;
    }

    /** Every move reports this many rows of its own until {@link #reset()}. */
    public void reportRows(int rows) {
        this.rows = rows;
    }

    public List<Call> before() {
        return List.copyOf(before);
    }

    public List<Call> after() {
        return List.copyOf(after);
    }

    public void reset() {
        veto = null;
        failAfter = null;
        whileMoving = null;
        rows = 0;
        before.clear();
        after.clear();
    }

    @Override
    public void beforeCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {
        before.add(new Call(customerId, fromRegionId, toRegionId));
        if (veto != null) throw new BadRequestException(veto);
    }

    @Override
    public int afterCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {
        after.add(new Call(customerId, fromRegionId, toRegionId));
        if (whileMoving != null) whileMoving.run();
        if (failAfter != null) throw new BadRequestException(failAfter);
        return rows;
    }
}
