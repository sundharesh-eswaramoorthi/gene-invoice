package com.geneinvoice.user;

import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.region.RegionDtos;
import com.geneinvoice.region.RegionGrantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Creates a staff account together with the region access it starts with, in one transaction: an account
 * saved without the grants it was meant to have could see nothing, and a retry would only be told the
 * username exists.
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserRepository userRepository;
    private final RegionGrantService regionGrantService;

    public record Created(User user, List<RegionDtos.UserGrantDto> regionGrants) {}

    /**
     * Absent or empty {@code grants} means the account holds no region at all (SC-5): access is given on
     * purpose, never by default. Each row is bounded by the creator's own grant in that region.
     */
    @Transactional
    public Created create(User unsaved, List<RegionDtos.GrantInput> grants) {
        ViewScopeHolder.requireLive();
        User saved = userRepository.save(unsaved);
        return new Created(saved, regionGrantService.grantNewUser(saved, grants));
    }
}
