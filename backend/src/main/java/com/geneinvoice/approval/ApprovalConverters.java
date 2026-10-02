package com.geneinvoice.approval;

import jakarta.persistence.AttributeConverter;

/**
 * The enum columns of {@code pending_changes} and {@code approval_thresholds}, stored as plain {@code varchar}
 * (D12): no CHECK and no H2 {@code ENUM} type, so appending a constant never needs a column widened.
 */
public final class ApprovalConverters {

    private ApprovalConverters() {}

    abstract static class ByName<E extends Enum<E>> implements AttributeConverter<E, String> {

        private final Class<E> type;

        ByName(Class<E> type) {
            this.type = type;
        }

        @Override
        public String convertToDatabaseColumn(E value) {
            return value == null ? null : value.name();
        }

        @Override
        public E convertToEntityAttribute(String name) {
            return name == null ? null : Enum.valueOf(type, name);
        }
    }

    public static class OperationName extends ByName<ApprovalOperation> {
        public OperationName() {
            super(ApprovalOperation.class);
        }
    }

    public static class StatusName extends ByName<PendingStatus> {
        public StatusName() {
            super(PendingStatus.class);
        }
    }

    public static class OriginName extends ByName<ChangeOrigin> {
        public OriginName() {
            super(ChangeOrigin.class);
        }
    }
}
