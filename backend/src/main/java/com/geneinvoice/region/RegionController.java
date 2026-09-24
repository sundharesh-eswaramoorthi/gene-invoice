package com.geneinvoice.region;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The region map, the caller's own roster, and who may work where.
 *
 * <p>Mapped at {@code /api} rather than at {@code /api/regions} because two of its six endpoints
 * reach a PERSON: a user's grants are read and written at /api/users/{id}/regions, where a client
 * that already has the user looks for them, and putting those routes in the user controller would
 * put region policy in a file that knows nothing about the ladder (B1).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RegionController {

    /** The same entity name RegionSchemaUpgrade writes its REGIONS_BACKFILLED row against (B1). */
    public static final String ENTITY = "REGION";

    private final RegionRepository regionRepository;
    private final UserRegionGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    // No controller in this application is @Transactional, and replacing a person's grants is a
    // delete and an insert that must not half-happen, so the one write that needs a transaction
    // asks for one explicitly — the DocumentService/EmailService idiom (B1).
    private final TransactionTemplate transactions;

    public record RegionDto(Long id, String code, String name, boolean active, Instant createdAt) {
        static RegionDto from(Region r) {
            return new RegionDto(r.getId(), r.getCode(), r.getName(), r.isActive(), r.getCreatedAt());
        }
    }

    /** The lengths are regions.code varchar(20) and regions.name varchar(120) (B1). */
    public record RegionUpsert(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 120) String name,
            Boolean active
    ) {}

    /**
     * The roster the UI needs. GET /api/pocs/my-scope requires POC_VIEW and cannot serve it, and
     * allRegions is reported separately from the named grants because expanding a wildcard would
     * be a query per sign-in and would go stale the day a branch is opened (B1).
     */
    public record MyRegionsDto(boolean allRegions, List<RegionDtos.RegionGrantDto> regions) {}

    /** A null regionId is the wildcard: every region, including ones created later (B1). */
    public record GrantInput(Long regionId, @NotBlank String right) {}

    public record SetGrantsRequest(List<GrantInput> grants) {}

    @GetMapping("/regions")
    @PreAuthorize("hasAuthority('" + Privileges.REGION_VIEW + "')")
    public PageResponse<RegionDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(RegionSchemas.REGIONS, page, size, sort,
                FilterParams.from(request));
        var result = queryExecutor.run(Region.class, RegionSchemas.REGIONS, query, List.of(), List.of());
        // Region is classified NONE, so lockedFilters() is empty here by construction: REGION_VIEW
        // is company-wide and the map is the same map for everybody who may see it (B1).
        return PageResponse.of(result.content().stream().map(RegionDto::from).toList(),
                query, result.total(), List.of(), List.of());
    }

    @GetMapping("/regions/my")
    @PreAuthorize("isAuthenticated()")
    public MyRegionsDto my() {
        RegionGrants grants = currentUser.grants();
        return new MyRegionsDto(grants.allRegions(RegionRight.VIEW),
                RegionDtos.heldBy(grants, regionRepository));
    }

    @PostMapping("/regions")
    @PreAuthorize("hasAuthority('" + Privileges.REGION_MANAGE + "')")
    public RegionDto create(@Valid @RequestBody RegionUpsert in) {
        String code = normalise(in.code());
        if (regionRepository.findByCode(code).isPresent()) {
            throw new BadRequestException("Region code already taken: " + code);
        }
        Region saved = regionRepository.save(Region.builder()
                .code(code).name(Strings.trim(in.name()))
                .active(in.active() == null || in.active())
                .build());
        auditService.record(ENTITY, saved.getId(), "REGION_CREATED", null, RegionDto.from(saved),
                currentUser.require().getId(), null, null);
        return RegionDto.from(saved);
    }

    @PutMapping("/regions/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.REGION_MANAGE + "')")
    public RegionDto update(@PathVariable Long id, @Valid @RequestBody RegionUpsert in) {
        Region region = regionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Region not found"));
        Object before = RegionDto.from(region);
        String code = normalise(in.code());
        // The code is what every grant, every import and every rejection message names a region
        // by, so a collision is refused rather than resolved (B1).
        regionRepository.findByCode(code).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new BadRequestException("Region code already taken: " + code);
            }
        });
        region.setCode(code);
        region.setName(Strings.trim(in.name()));
        // Retiring a region hides it from the pickers; it never rewrites the customers that live
        // there, and nothing deletes one, so every historical placement stays readable (B1).
        if (in.active() != null) region.setActive(in.active());
        Region saved = regionRepository.save(region);
        auditService.record(ENTITY, id, "REGION_UPDATED", before, RegionDto.from(saved),
                currentUser.require().getId(), null, null);
        return RegionDto.from(saved);
    }

    /**
     * READ one person's grants. The pair of {@link #setGrants}, and it exists because that writer
     * REPLACES the whole set: an editor that cannot show what somebody holds today can only ask an
     * administrator to compose the set from memory, and an administrator who opens it to add one
     * branch silently strips the rest — the wildcard included, which is reach nothing short of
     * another administrator can give back (B1).
     *
     * <p>GET /api/regions/my cannot serve this: it answers for the CALLER, off the principal, and
     * the question here is about somebody else, which is a table read (RegionGrants).
     *
     * <p>The VIEW pair and not the MANAGE pair the writer asks for, because seeing who may work
     * where is strictly less than deciding it: a reader must need no more than the writer, and
     * anybody who may administer people and regions already reads both. Both privileges are
     * company-wide (RegionRights.COMPANY_WIDE), so the authority-drop rule never takes them off a
     * staffed account and this gate means the same thing in every branch (B1).
     *
     * <p>A customer login holds no grants BY DESIGN — its reach is its own account, decided by
     * customer_id and the POC book — so it answers the EMPTY roster rather than anything invented.
     * Empty is a real, renderable state and is also what somebody created but not yet given a
     * branch answers; allRegions beside it is what tells "nowhere" apart from "everywhere" (B1).
     */
    @GetMapping("/users/{id}/regions")
    @PreAuthorize("hasAuthority('" + Privileges.USER_VIEW + "') and hasAuthority('"
            + Privileges.REGION_VIEW + "')")
    public MyRegionsDto grantsOf(@PathVariable Long id) {
        // existsById, and 404 for an id that is no person, exactly as the writer below answers: a
        // client that may reach one of the pair cannot probe the id space through the other (B1).
        if (!userRepository.existsById(id)) {
            throw new NotFoundException("User not found");
        }
        return roster(id);
    }

    /**
     * Replace one person's grants. Both privileges are required because this edits a person AND
     * hands out region reach, and either one alone would be half an answer (B1).
     */
    @PutMapping("/users/{id}/regions")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "') and hasAuthority('"
            + Privileges.REGION_MANAGE + "')")
    public MyRegionsDto setGrants(@PathVariable Long id, @Valid @RequestBody SetGrantsRequest in) {
        // existsById and NOT findById: User.regionGrants is EAGER with cascade=ALL, so a managed
        // subject whose collection still holds a grant this call deletes has that grant cascaded
        // back in at flush and the edit silently does nothing. Nothing here needs the person, only
        // that they are one (B1).
        if (!userRepository.existsById(id)) {
            throw new NotFoundException("User not found");
        }
        Long actor = currentUser.require().getId();
        Set<Key> wanted = requested(in);

        Change change = transactions.execute(status -> {
            List<UserRegionGrant> held = grantRepository.findByUserId(id);
            // Both guards read the grants this call is about to replace, so they run HERE, inside
            // the transaction and before the first delete: a refusal must leave the row set
            // exactly as it found it (B1, B2).
            requireNotSelfWidening(id, actor, held, wanted);
            requireWildcardApproverRemains(id, held, wanted);
            // Snapshotted INSIDE the transaction: the rows this call drops are gone by the time
            // the audit row is written, so the before side is taken while they are still here (B1).
            List<Key> before = held.stream().map(Key::of).toList();
            // A diff rather than a delete-and-recreate: an unchanged grant keeps its createdAt and
            // its grantedByUserId, and nothing is briefly absent for a concurrent reader (B1).
            List<UserRegionGrant> drop = held.stream()
                    .filter(g -> !wanted.contains(Key.of(g))).toList();
            grantRepository.deleteAll(drop);
            // Flushed before the inserts, because Hibernate's action queue runs deletes LAST and
            // re-adding a grant dropped in the same call would collide with the Postgres partial
            // unique indexes on user_region_grants (B1).
            grantRepository.flush();

            Set<Key> present = new LinkedHashSet<>(
                    held.stream().filter(g -> wanted.contains(Key.of(g))).map(Key::of).toList());
            List<UserRegionGrant> added = new ArrayList<>();
            for (Key k : wanted) {
                if (present.contains(k)) continue;
                added.add(UserRegionGrant.builder()
                        .userId(id).regionId(k.regionId()).right(k.right())
                        .grantedByUserId(actor).build());
            }
            grantRepository.saveAll(added);
            return new Change(before, List.copyOf(wanted));
        });

        MyRegionsDto now = roster(id);
        auditService.record("USER", id, "USER_REGIONS_CHANGED",
                lines(change.before()), lines(change.after()), actor, null, null);
        return now;
    }

    /**
     * One person's grants in the shape GET /api/regions/my answers, read from the TABLE rather
     * than off a principal because the subject is somebody else. One builder for both the reader
     * and the writer, so what an editor shows and what a save answers can never drift apart (B1).
     */
    private MyRegionsDto roster(Long userId) {
        RegionGrants held = RegionGrants.of(grantRepository.findByUserId(userId));
        return new MyRegionsDto(held.allRegions(RegionRight.VIEW),
                RegionDtos.heldBy(held, regionRepository));
    }

    /**
     * YOU CANNOT HAND YOURSELF THE COMPANY (B1, B2, D-46).
     *
     * <p>This endpoint needs USER_MANAGE and REGION_MANAGE, and nothing in the pair says whose
     * grants may be edited — so a person trusted with two branches could write themselves a
     * null-region row and be trusted with every branch there is and every branch there will ever
     * be, on nobody's say-so but their own. Widening somebody ELSE is untouched, because that is
     * a second pair of eyes by construction: the person gaining the reach and the person granting
     * it are two people.
     *
     * <p>A right the caller ALREADY holds everywhere is not a widening and goes through, which is
     * what keeps the ordinary "re-save my own grants" edit working, and the test is
     * {@link RegionGrants#allRegions} and not set membership so that the ladder is honoured: a
     * wildcard APPROVE holder may write themselves wildcard VIEW, because APPROVE already covers
     * VIEW, and may NOT write themselves wildcard MANAGE, because APPROVE does not cover MANAGE.
     *
     * <p>403 and not 404: the caller named themselves, so no id space is being probed (D-46).
     */
    private void requireNotSelfWidening(Long subjectId, Long actor,
                                        List<UserRegionGrant> held, Set<Key> wanted) {
        if (!actor.equals(subjectId)) return;
        RegionGrants already = RegionGrants.of(held);
        for (Key k : wanted) {
            if (k.regionId() == null && !already.allRegions(k.right())) {
                throw new AccessDeniedException("You cannot give yourself "
                        + k.right().name().toLowerCase(Locale.ROOT)
                        + " in every branch; ask somebody who already holds it to grant it to you");
            }
        }
    }

    /**
     * THE LAST EVERY-BRANCH APPROVER STAYS (B1, B2).
     *
     * <p>This call REPLACES the whole grant set, so a set that simply omits the wildcard APPROVE
     * row deletes it, and the editor that composes the set cannot express two rights for one
     * branch — which makes the omission the easy accident rather than the deliberate act. Lose
     * the last one and B2's queue has no approver of last resort for a branch nobody named:
     * APPROVE is not covered by MANAGE, a change raised in a branch can only be decided by
     * somebody holding APPROVE there, and no remaining account could grant it back, because
     * {@link #requireNotSelfWidening} stops anyone handing it to themselves. That is an
     * installation locked out of its own four-eyes gate, and nothing short of SQL undoes it.
     *
     * <p>Only the wildcard is guarded, and only at APPROVE. A NAMED branch losing its last
     * approver is recoverable — any wildcard holder can grant it back — and MANAGE is not the
     * right that can strand a decision, so neither is refused here.
     */
    private void requireWildcardApproverRemains(Long subjectId,
                                                List<UserRegionGrant> held, Set<Key> wanted) {
        boolean holdsIt = held.stream().anyMatch(
                g -> g.getRegionId() == null && g.getRight() == RegionRight.APPROVE);
        // Not held, or kept: nothing is being taken away and there is nothing to count.
        if (!holdsIt || wanted.contains(new Key(null, RegionRight.APPROVE))) return;
        if (grantRepository.countOtherWildcardHolders(RegionRight.APPROVE, subjectId) == 0) {
            throw new AccessDeniedException("This is the last active account that can approve in"
                    + " every branch; give another account approve in every branch first");
        }
    }

    private record Change(List<Key> before, List<Key> after) {}

    /**
     * The audit shape. It is NOT RegionGrantDto, because heldBy() reports only the NAMED grants
     * and the one change that matters most — somebody being given or losing the wildcard — would
     * be invisible in the blob (B1).
     */
    public record GrantLine(Long regionId, String code, String right) {}

    private List<GrantLine> lines(List<Key> keys) {
        Map<Long, String> codes = new LinkedHashMap<>();
        regionRepository.findAllById(keys.stream()
                        .map(Key::regionId).filter(Objects::nonNull).distinct().toList())
                .forEach(r -> codes.put(r.getId(), r.getCode()));
        return keys.stream()
                .sorted(Comparator.comparing((Key k) -> k.regionId() == null ? "" : String.valueOf(k.regionId()))
                        .thenComparing(k -> k.right().name()))
                // "*" and not a null code: a wildcard row is every region, including ones opened
                // after it was written, and a reviewer must see that at a glance (B1).
                .map(k -> new GrantLine(k.regionId(),
                        k.regionId() == null ? "*" : codes.get(k.regionId()), k.right().name()))
                .toList();
    }

    private static String normalise(String raw) {
        // Upper-cased as well as trimmed: "north" and "NORTH" are one branch, and uk_region_code
        // is an exact-match constraint that would happily hold both (B1).
        String code = Strings.trim(raw);
        return code == null ? null : code.toUpperCase(Locale.ROOT);
    }

    private Set<Key> requested(SetGrantsRequest in) {
        Set<Key> wanted = new LinkedHashSet<>();
        if (in.grants() == null) return wanted;
        for (GrantInput g : in.grants()) {
            // A named region that does not exist is not found, exactly as a missing record is
            // anywhere else; the wildcard names none and is always valid (B1).
            if (g.regionId() != null && !regionRepository.existsById(g.regionId())) {
                throw new NotFoundException("Region not found: " + g.regionId());
            }
            wanted.add(new Key(g.regionId(), RegionRight.parse(g.right())));
        }
        return wanted;
    }

    private record Key(Long regionId, RegionRight right) {
        static Key of(UserRegionGrant g) {
            return new Key(g.getRegionId(), g.getRight());
        }
    }
}
