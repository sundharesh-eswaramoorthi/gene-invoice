package com.geneinvoice.region;

import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Regions are configuration: they answer live whatever date is viewed (D9). Whoever manages users may read
 * them too, to choose what a user is granted.
 */
@RestController
@AsOfIgnored
@RequestMapping("/api/regions")
@RequiredArgsConstructor
public class RegionController {

    private static final String READ = "hasAnyAuthority('" + Privileges.REGION_VIEW + "', '"
            + Privileges.USER_MANAGE + "')";

    private final RegionService regionService;
    private final TableQueryExecutor queryExecutor;

    @GetMapping
    @PreAuthorize(READ)
    public PageResponse<RegionDtos.RegionDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(TableSchemas.REGIONS, page, size, sort, FilterParams.from(request));
        var result = queryExecutor.run(Region.class, TableSchemas.REGIONS, query, List.of(), List.of());
        return PageResponse.of(result.content().stream().map(RegionDtos.RegionDto::from).toList(),
                query, result.total(), List.of());
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public RegionDtos.RegionDto get(@PathVariable Long id) {
        return RegionDtos.RegionDto.from(regionService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.REGION_MANAGE + "')")
    public RegionDtos.RegionDto create(@Valid @RequestBody RegionDtos.CreateRegionRequest in) {
        return RegionDtos.RegionDto.from(regionService.create(in));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.REGION_MANAGE + "')")
    public RegionDtos.RegionDto update(@PathVariable Long id, @Valid @RequestBody RegionDtos.UpdateRegionRequest in) {
        return RegionDtos.RegionDto.from(regionService.update(id, in));
    }

    @PostMapping("/{id}/make-default")
    @PreAuthorize("hasAuthority('" + Privileges.REGION_MANAGE + "')")
    public RegionDtos.RegionDto makeDefault(@PathVariable Long id) {
        return RegionDtos.RegionDto.from(regionService.makeDefault(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.REGION_MANAGE + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        regionService.delete(id);
    }
}
