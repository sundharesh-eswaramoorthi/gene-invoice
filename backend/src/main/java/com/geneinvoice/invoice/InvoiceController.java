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
        return service.listSummaries(customerId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public InvoiceDtos.InvoiceDto get(@PathVariable Long id) {
        return InvoiceDtos.InvoiceDto.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto create(@Valid @RequestBody InvoiceDtos.CreateInvoiceRequest req) {
        return InvoiceDtos.InvoiceDto.from(service.create(req));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto cancel(@PathVariable Long id) {
        return InvoiceDtos.InvoiceDto.from(service.cancel(id));
    }
}
