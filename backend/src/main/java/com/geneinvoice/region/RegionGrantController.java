package com.geneinvoice.region;

import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Who holds which region. Grants are configuration: they answer live whatever date is viewed (D9). */
@RestController
@AsOfIgnored
@RequestMapping("/api")
@RequiredArgsConstructor
public class RegionGrantController {

    private final RegionGrantService grantService;

    @GetMapping("/regions/{id}/grants")
    @PreAuthorize("hasAuthority('" + Privileges.REGION_VIEW + "') and hasAuthority('" + Privileges.USER_VIEW + "')")
    public List<RegionDtos.RegionGrantHolderDto> holders(@PathVariable Long id) {
        return grantService.holdersOf(id);
    }

    @GetMapping("/users/{id}/region-grants")
    @PreAuthorize("hasAuthority('" + Privileges.USER_VIEW + "')")
    public List<RegionDtos.UserGrantDto> grantsOf(@PathVariable Long id) {
        return grantService.grantsOf(id);
    }

    @PutMapping("/users/{id}/region-grants")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public List<RegionDtos.UserGrantDto> replace(@PathVariable Long id,
                                                 @Valid @RequestBody RegionDtos.ReplaceGrantsRequest in) {
        return grantService.replace(id, in.grants());
    }
}
