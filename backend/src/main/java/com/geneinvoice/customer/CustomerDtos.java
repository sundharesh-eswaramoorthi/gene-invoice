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
            PaymentTerm paymentTerm, String paymentTermLabel,
            BigDecimal outstanding,
            BigDecimal overdueAmount,
            List<PocDtos.CustomerPocDto> successPocs,
            List<PocDtos.CustomerPocDto> collectionPocs,
            Boolean pocMissing,
            Instant createdAt,
            // Trailing, in the order the blueprint fixes so B2 can append approvalPending after
            // them. A customer's region is its own — this is the only region column there is (B1).
            Long regionId,
            String regionName,
            // Is there a change waiting on this account — a held delete, or a held payment, or
            // anything else raised against it? Trailing, after B1's two slots, null meaning
            // "not asked" (B2).
            Boolean approvalPending
    ) {
        /** B1's two region slots emptied, for a customer login — see InvoiceDto.withoutRegion. */
        public CustomerDto withoutRegion() {
            return new CustomerDto(id, name, phone, email, address, creditBalance, username,
                    paymentTerm, paymentTermLabel, outstanding, overdueAmount, successPocs,
                    collectionPocs, pocMissing, createdAt, null, null, approvalPending);
        }
    }

    // regionId(Customer) / regionName(Customer) moved onto Customer.getRegionId()/getRegionName(),
    // WHY comment and null-tolerance intact, because a mirror row has no Customer to hand one and
    // the view has to be able to answer for itself (B1, B3).

    public record CustomerCreateRequest(
            @NotBlank @Size(max = FieldLimits.FULL_NAME) String name,
            @Size(max = FieldLimits.PHONE) String phone,
            @Email @Size(max = FieldLimits.EMAIL) String email,
            @Size(max = FieldLimits.ADDRESS) String address,
            PaymentTerm paymentTerm,
            // The branch this account is opened in. Null is answered from the caller's own grants
            // when there is exactly one answer and is refused with a field error when there is
            // not — never guessed, because an account filed in the wrong branch is invisible to
            // the people who should be working it (B1).
            Long regionId,
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

    public record CustomerSummaryTiles(
            long count,
            BigDecimal totalOutstanding,
            BigDecimal totalCreditBalance,
            long missingSuccessPocCount,
            long missingCollectionPocCount,
            // Trailing, the blueprint's fixed order for a tile. A COUNT and never an amount (B2).
            long awaitingApprovalCount
    ) {}
}
