package com.geneinvoice.invoice;

import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService service;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public List<InvoiceDtos.InvoiceSummary> list(@RequestParam(required = false) Long customerId) {
        var invoices = customerId != null ? service.listByCustomer(customerId) : service.list();
        return invoices.stream()
                .map(inv -> InvoiceDtos.InvoiceSummary.from(inv, service.activeCreditedAmount(inv.getId())))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public InvoiceDtos.InvoiceDto get(@PathVariable Long id) {
        var inv = service.get(id);
        return InvoiceDtos.InvoiceDto.from(inv, service.activeCreditedAmount(inv.getId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto create(@Valid @RequestBody InvoiceDtos.CreateInvoiceRequest req) {
        var inv = service.create(req);
        return InvoiceDtos.InvoiceDto.from(inv, service.activeCreditedAmount(inv.getId()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto cancel(@PathVariable Long id) {
        var inv = service.cancel(id);
        return InvoiceDtos.InvoiceDto.from(inv, service.activeCreditedAmount(inv.getId()));
    }
}
