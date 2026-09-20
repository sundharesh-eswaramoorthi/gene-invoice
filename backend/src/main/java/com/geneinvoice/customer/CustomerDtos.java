package com.geneinvoice.customer;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.poc.PocDtos;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class CustomerDtos {

    public record CustomerDto(
            Long id, String name, String phone, String email,
            String address, BigDecimal creditBalance, String username,
            /** Null means "use the system default"; the label names whichever applies. */
            PaymentTerm paymentTerm, String paymentTermLabel,
            BigDecimal outstanding,
            /** How much of the outstanding total is past its due date (AC-A6). */
            BigDecimal overdueAmount,
            /** Null for a customer-scoped caller, who never sees POC identity (AC-A8). */
            List<PocDtos.CustomerPocDto> successPocs,
            List<PocDtos.CustomerPocDto> collectionPocs,
            /** True when the customer holds no POC seat of either kind (AC-A9). */
            Boolean pocMissing,
            Instant createdAt
    ) {}

    public record CustomerCreateRequest(
            @NotBlank @Size(max = FieldLimits.FULL_NAME) String name,
            @Size(max = FieldLimits.PHONE) String phone,
            @Email @Size(max = FieldLimits.EMAIL) String email,
            @Size(max = FieldLimits.ADDRESS) String address,
            /** Omitted means the system default; CUSTOM is refused (§2.1). */
            PaymentTerm paymentTerm,
            @NotBlank @Size(max = FieldLimits.USERNAME) String username,
            @NotBlank String password
    ) {}

    public record CustomerUpdateRequest(
            @NotBlank @Size(max = FieldLimits.FULL_NAME) String name,
            @Size(max = FieldLimits.PHONE) String phone,
            @Email @Size(max = FieldLimits.EMAIL) String email,
            @Size(max = FieldLimits.ADDRESS) String address,
            PaymentTerm paymentTerm,
            String password
    ) {}

    /** Filter-aware tiles for the customers list (Feature E). */
    public record CustomerSummaryTiles(
            long count,
            BigDecimal totalOutstanding,
            BigDecimal totalCreditBalance,
            long missingSuccessPocCount,
            long missingCollectionPocCount
    ) {}
}
