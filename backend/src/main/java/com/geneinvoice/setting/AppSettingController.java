package com.geneinvoice.setting;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Administrator-maintained application settings. Gated on the existing role-administration
 * authority — no new privilege vocabulary (DES-EMAIL-07). An unset admin email is a legal state:
 * only a send that actually needs the fallback is rejected for it.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class AppSettingController {

    private final AppSettingRepository repository;

    public record AdminEmailView(String adminEmail) {}

    /** Blank clears the setting; a non-blank value must look like an email address. */
    public record AdminEmailUpdate(@Size(max = FieldLimits.EMAIL) String adminEmail) {}

    @GetMapping("/admin-email")
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_MANAGE + "')")
    public AdminEmailView getAdminEmail() {
        return new AdminEmailView(readValue());
    }

    @PutMapping("/admin-email")
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_MANAGE + "')")
    public AdminEmailView putAdminEmail(@Valid @RequestBody AdminEmailUpdate in) {
        String normalized = Emails.normalize(in.adminEmail());
        if (normalized != null && !Emails.isValid(normalized)) {
            throw new BadRequestException("Admin email must be a valid email address");
        }
        AppSetting setting = repository.findById(AppSettingKeys.ADMIN_EMAIL)
                .orElseGet(() -> AppSetting.builder().key(AppSettingKeys.ADMIN_EMAIL).build());
        setting.setValue(normalized);
        repository.save(setting);
        return new AdminEmailView(normalized);
    }

    private String readValue() {
        return repository.findById(AppSettingKeys.ADMIN_EMAIL)
                .map(s -> Emails.normalize(s.getValue()))
                .orElse(null);
    }
}
