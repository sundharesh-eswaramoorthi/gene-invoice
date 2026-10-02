package com.geneinvoice.customer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
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
            @JsonInclude(JsonInclude.Include.NON_NULL) Long regionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionName,
            /** What the caller may do to this customer in its region (D14); null in audit snapshots. */
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canManage,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canMoveRegion,
            /** The row version a region move sends back, so a move made from a stale page is refused (409). */
            @JsonInclude(JsonInclude.Include.NON_NULL) Long version
    ) {}

    public record CustomerCreateRequest(
            @NotBlank @Size(max = FieldLimits.FULL_NAME) String name,
            @Size(max = FieldLimits.PHONE) String phone,
            @Email @Size(max = FieldLimits.EMAIL) String email,
            @Size(max = FieldLimits.ADDRESS) String address,
            PaymentTerm paymentTerm,
            @NotBlank @Size(max = FieldLimits.USERNAME) String username,
            @NotBlank String password,
            /** Optional when the caller can manage customers in exactly one active region. */
            Long regionId
    ) {
        @JsonCreator
        public CustomerCreateRequest {
        }

        public CustomerCreateRequest(String name, String phone, String email, String address,
                                     PaymentTerm paymentTerm, String username, String password) {
            this(name, phone, email, address, paymentTerm, username, password, null);
        }
    }

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
            long missingCollectionPocCount
    ) {}
}
