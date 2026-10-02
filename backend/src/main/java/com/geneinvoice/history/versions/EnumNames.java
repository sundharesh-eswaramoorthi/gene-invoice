package com.geneinvoice.history.versions;

import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskStatus;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

/**
 * Enum columns of version tables, stored as plain {@code varchar}: no CHECK and no H2 {@code ENUM} type, so a
 * constant added to an enum never needs its version table widened, and a name the enum has since lost reads
 * back as null instead of failing the whole read.
 */
@Slf4j
public final class EnumNames {

    private EnumNames() {}

    public abstract static class EnumName<E extends Enum<E>> implements AttributeConverter<E, String> {

        private final Class<E> type;

        protected EnumName(Class<E> type) {
            this.type = type;
        }

        @Override
        public String convertToDatabaseColumn(E value) {
            return value == null ? null : value.name();
        }

        @Override
        public E convertToEntityAttribute(String name) {
            if (name == null) return null;
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException unknown) {
                log.warn("history.enum.unknown {} {}", type.getSimpleName(), name);
                return null;
            }
        }
    }

    public static class InvoiceStatusName extends EnumName<InvoiceStatus> {
        public InvoiceStatusName() {
            super(InvoiceStatus.class);
        }
    }

    public static class PaymentTermName extends EnumName<PaymentTerm> {
        public PaymentTermName() {
            super(PaymentTerm.class);
        }
    }

    public static class PaymentStatusName extends EnumName<PaymentStatus> {
        public PaymentStatusName() {
            super(PaymentStatus.class);
        }
    }

    public static class PromiseStatusName extends EnumName<PromiseStatus> {
        public PromiseStatusName() {
            super(PromiseStatus.class);
        }
    }

    public static class PocTypeName extends EnumName<PocType> {
        public PocTypeName() {
            super(PocType.class);
        }
    }

    public static class DisputeTargetTypeName extends EnumName<DisputeTargetType> {
        public DisputeTargetTypeName() {
            super(DisputeTargetType.class);
        }
    }

    public static class DisputeStatusName extends EnumName<DisputeStatus> {
        public DisputeStatusName() {
            super(DisputeStatus.class);
        }
    }

    public static class TaskStatusName extends EnumName<TaskStatus> {
        public TaskStatusName() {
            super(TaskStatus.class);
        }
    }

    public static class TaskEntityTypeName extends EnumName<TaskEntityType> {
        public TaskEntityTypeName() {
            super(TaskEntityType.class);
        }
    }
}
