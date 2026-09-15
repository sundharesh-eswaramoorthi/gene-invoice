package com.geneinvoice.customer;

import com.geneinvoice.common.FieldLimits;
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
            BigDecimal outstanding,
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
            @NotBlank @Size(max = FieldLimits.USERNAME) String username,
            @NotBlank String password
    ) {}

    public record CustomerUpdateRequest(
            @NotBlank @Size(max = FieldLimits.FULL_NAME) String name,
            @Size(max = FieldLimits.PHONE) String phone,
            @Email @Size(max = FieldLimits.EMAIL) String email,
            @Size(max = FieldLimits.ADDRESS) String address,
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
