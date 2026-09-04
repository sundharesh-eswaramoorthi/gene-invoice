package com.geneinvoice.payment;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final CurrentUser currentUser;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public List<PaymentDtos.PaymentDto> list(@RequestParam(required = false) Long customerId) {
        Long callerCustomer = currentUser.customerIdOrNull();
        Long effective;
        if (callerCustomer != null) {
            if (customerId != null && !customerId.equals(callerCustomer)) {
                throw new AccessDeniedException("Not allowed");
            }
            effective = callerCustomer;
        } else {
            effective = customerId;
        }
        return paymentService.list(effective).stream()
                .map(p -> PaymentDtos.PaymentDto.from(p, invoiceService::creditedAmount))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public PaymentDtos.PaymentDto get(@PathVariable Long id) {
        Payment p = paymentService.get(id);
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(p.getCustomer().getId())) {
            throw new AccessDeniedException("Not allowed");
        }
        return PaymentDtos.PaymentDto.from(p, invoiceService::creditedAmount);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_MANAGE + "')")
    public PaymentDtos.PaymentDto create(@Valid @RequestBody PaymentDtos.CreatePaymentRequest req) {
        return PaymentDtos.PaymentDto.from(paymentService.record(req), invoiceService::creditedAmount);
    }

    public record CustomerCreditDto(Long customerId, String customerName, BigDecimal creditBalance) {}

    @GetMapping("/credits/{customerId}")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public CustomerCreditDto credit(@PathVariable Long customerId) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(customerId)) {
            throw new AccessDeniedException("Not allowed");
        }
        Customer c = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        return new CustomerCreditDto(c.getId(), c.getName(), c.getCreditBalance());
    }
}
