package com.geneinvoice.automation.placeholder;

import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.privilege.Privileges;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The placeholders offered on a kind of record (§6.14): for rule texts, and for emails written by hand. */
@RestController
@AsOfIgnored
@RequestMapping("/api/automation/placeholders")
public class PlaceholderController {

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + Privileges.AUTOMATION_VIEW + "', '" + Privileges.TASK_MANAGE + "', '"
            + Privileges.EMAIL_SEND + "')")
    public PlaceholderCatalogue.CatalogueDto catalogue(@RequestParam String entityType) {
        return PlaceholderCatalogue.catalogue(entityType);
    }
}
