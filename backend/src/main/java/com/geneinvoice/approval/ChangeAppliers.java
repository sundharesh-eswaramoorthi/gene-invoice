package com.geneinvoice.approval;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The applier of each operation; an operation nothing applies cannot be approved. */
@Component
public class ChangeAppliers {

    private final Map<ApprovalOperation, ChangeApplier> byOperation = new EnumMap<>(ApprovalOperation.class);

    public ChangeAppliers(List<ChangeApplier> appliers) {
        for (ChangeApplier applier : appliers) {
            for (ApprovalOperation op : applier.operations()) {
                if (byOperation.putIfAbsent(op, applier) != null) {
                    throw new IllegalStateException("Two appliers for " + op);
                }
            }
        }
    }

    public ChangeApplier of(ApprovalOperation op) {
        ChangeApplier applier = byOperation.get(op);
        if (applier == null) throw new IllegalStateException("Nothing applies " + op + " yet");
        return applier;
    }
}
