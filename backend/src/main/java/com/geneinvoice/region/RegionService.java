package com.geneinvoice.region;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RegionService {

    public static final String ENTITY = "REGION";

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{2,20}$");

    private final RegionRepository regionRepository;
    private final UserRegionGrantRepository grantRepository;
    private final RegionGrantService regionGrantService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final ObjectProvider<RegionCreatedListener> createdListeners;
    private final ObjectProvider<RegionDeletingListener> deletingListeners;

    /**
     * Looked up on every call, never cached: the default can change, and a second test context wipes and
     * re-seeds the database under a running one (R10).
     */
    @Transactional(readOnly = true)
    public Region defaultRegion() {
        return regionRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("No default region; RegionSchemaUpgrade has not run"));
    }

    @Transactional(readOnly = true)
    public Region requireActive(Long id) {
        Region region = get(id);
        if (!region.isActive()) {
            throw new BadRequestException("Region " + region.getName() + " is inactive");
        }
        return region;
    }

    @Transactional(readOnly = true)
    public Region get(Long id) {
        return regionRepository.findById(id).orElseThrow(() -> new NotFoundException("Region not found"));
    }

    /** Read under the row lock a make-default also takes, so what is checked is still true when it is written. */
    private Region lock(Long id) {
        return regionRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Region not found"));
    }

    /**
     * The creator is given the new region at APPROVE/ALL, so whoever made it can put customers and people in
     * it straight away; nobody else holds it until they are granted it.
     */
    @Transactional
    public Region create(RegionDtos.CreateRegionRequest in) {
        ViewScopeHolder.requireLive();
        String code = in.code().trim().toUpperCase(Locale.ROOT);
        String name = in.name().trim();
        if (!CODE.matcher(code).matches()) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("code",
                    "Use 2 to 20 capital letters, digits, hyphens or underscores"));
        }
        if (regionRepository.findByCode(code).isPresent()) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("code",
                    "A region with this code already exists"));
        }
        if (regionRepository.existsByNameIgnoreCase(name)) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("name",
                    "A region with this name already exists"));
        }
        Region saved = regionRepository.save(Region.builder()
                .code(code)
                .name(name)
                .active(in.active() == null || in.active())
                .defaultRegion(false)
                .build());
        User creator = currentUser.require();
        if (creator.getCustomerId() == null) {
            regionGrantService.ensure(creator, saved, AccessLevel.APPROVE, RecordScope.ALL);
        }
        createdListeners.orderedStream().forEach(l -> l.regionCreated(saved));
        auditService.record(ENTITY, saved.getId(), "REGION_CREATED", null, RegionDtos.RegionDto.from(saved),
                creator.getId(), null, null);
        return saved;
    }

    @Transactional
    public Region update(Long id, RegionDtos.UpdateRegionRequest in) {
        ViewScopeHolder.requireLive();
        Region region = lock(id);
        // A save composed against an older version is refused, not applied over what was saved since.
        if (!in.version().equals(region.getVersion())) {
            throw new OptimisticLockingFailureException("Region " + id + " changed since it was read");
        }
        String name = in.name().trim();
        if (!name.equalsIgnoreCase(region.getName()) && regionRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("name",
                    "A region with this name already exists"));
        }
        if (Boolean.FALSE.equals(in.active()) && region.isDefaultRegion()) {
            throw new BadRequestException("The default region cannot be deactivated");
        }
        RegionDtos.RegionDto before = RegionDtos.RegionDto.from(region);
        region.setName(name);
        if (in.active() != null) region.setActive(in.active());
        Region saved = regionRepository.saveAndFlush(region);
        auditService.record(ENTITY, id, "REGION_UPDATED", before, RegionDtos.RegionDto.from(saved),
                currentUser.require().getId(), null, null);
        return saved;
    }

    /**
     * New customers of a caller who names no region, and records from before regions, go to the default. The
     * region and the old default are locked first, so a concurrent make-default, edit or delete waits its turn.
     */
    @Transactional
    public Region makeDefault(Long id) {
        ViewScopeHolder.requireLive();
        Region region = regionRepository.lockWithDefault(id).stream().filter(r -> r.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Region not found"));
        if (!region.isActive()) {
            throw new BadRequestException("Region " + region.getName()
                    + " is inactive; activate it before making it the default");
        }
        if (region.isDefaultRegion()) return region;
        RegionDtos.RegionDto before = RegionDtos.RegionDto.from(region);
        String previous = regionRepository.findByIsDefaultTrue().map(Region::getCode).orElse(null);
        // Cleared first: Postgres checks its one-default index statement by statement.
        regionRepository.clearDefaultExcept(id);
        regionRepository.markDefault(id);
        Region saved = get(id);
        auditService.record(ENTITY, id, "REGION_DEFAULT_CHANGED", before, RegionDtos.RegionDto.from(saved),
                currentUser.require().getId(), null,
                previous == null ? null : "Previously the default: " + previous);
        return saved;
    }

    /**
     * Only an empty region can go: records in it would be left nowhere, and people granted it would silently
     * lose access. The caller's own grant (a creator always has one) goes with it, and so does what belongs to the
     * region alone (its approval thresholds and decided changes of them).
     */
    @Transactional
    public void delete(Long id) {
        ViewScopeHolder.requireLive();
        Region region = lock(id);
        if (region.isDefaultRegion()) {
            throw new BadRequestException("Region is the default");
        }
        Long caller = currentUser.require().getId();
        if (regionRepository.hasCustomers(id) || regionRepository.hasDisputes(id)
                || grantRepository.countByRegionIdAndUserIdNot(id, caller) > 0) {
            throw new BadRequestException("Region still has customers, disputes or grants; deactivate it instead");
        }
        RegionDtos.RegionDto before = RegionDtos.RegionDto.from(region);
        deletingListeners.orderedStream().forEach(l -> l.regionDeleting(region));
        grantRepository.deleteAllInRegion(id);
        regionRepository.delete(region);
        // Now, so a row pointing at it is reported as a conflict (409) rather than failing the commit.
        regionRepository.flush();
        auditService.record(ENTITY, id, "REGION_DELETED", before, null, caller, null, null);
    }
}
