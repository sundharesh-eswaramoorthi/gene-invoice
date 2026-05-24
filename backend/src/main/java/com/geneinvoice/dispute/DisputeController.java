package com.geneinvoice.dispute;

import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService service;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public List<DisputeDtos.DisputeDto> list() {
        return service.list().stream().map(service::toDto).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_VIEW + "')")
    public DisputeDtos.DisputeDto get(@PathVariable Long id) {
        return service.toDto(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_CREATE + "')")
    public DisputeDtos.DisputeDto create(@Valid @RequestBody DisputeDtos.CreateDisputeRequest req) {
        return service.toDto(service.open(req));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public DisputeDtos.DisputeDto approve(@PathVariable Long id,
                                          @RequestBody(required = false) DisputeDtos.ResolveDisputeRequest req) {
        return service.toDto(service.approve(id, req));
    }

    @PostMapping("/{id}/deny")
    @PreAuthorize("hasAuthority('" + Privileges.DISPUTE_MANAGE + "')")
    public DisputeDtos.DisputeDto deny(@PathVariable Long id,
                                       @RequestBody(required = false) DisputeDtos.ResolveDisputeRequest req) {
        return service.toDto(service.deny(id, req));
    }
}
