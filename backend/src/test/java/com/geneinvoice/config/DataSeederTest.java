package com.geneinvoice.config;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.region.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a RESTART does. DataSeeder is a CommandLineRunner, so it runs once per cached Spring
 * context and a @BeforeEach can never re-run it — which is why nothing in the suite had ever asked
 * what its second run does to an installation that has moved on since its first (B1).
 */
class DataSeederTest extends IntegrationTestBase {

    @Autowired DataSeeder dataSeeder;

    private Long renamed;

    @AfterEach
    void putTheDefaultBranchBack() {
        // The default region is reference data the whole suite shares: IntegrationTestBase keeps
        // the one whose code matches the configuration and deletes every other, so a test that
        // renames it has to hand it back under its own name.
        if (renamed == null) return;
        regionRepository.findById(renamed).ifPresent(r -> {
            r.setCode(regionProperties.defaultCode());
            r.setName(regionProperties.defaultName());
            regionRepository.saveAndFlush(r);
        });
        renamed = null;
    }

    @Test
    void renamingTheDefaultBranchDoesNotHandBackASecondOneOnTheNextBoot() {
        Region home = defaultRegion();
        renamed = home.getId();
        // Exactly what PUT /api/regions/{id} does, which is a shipped endpoint with no guard on
        // the default region at all.
        home.setCode("LONDON");
        home.setName("London Branch");
        regionRepository.saveAndFlush(home);
        long before = regionRepository.count();

        dataSeeder.run();

        // A find-or-create keyed on app.regions.default-code would make a brand-new active branch
        // under the old code here, and it would then be in GET /api/regions, in every branch
        // picker, and competing to be where a new account is filed. The row is seeded ONCE by
        // code, which is why application.yml calls renaming it a data change (B1).
        assertThat(regionRepository.count()).isEqualTo(before);
        assertThat(regionRepository.findByCode(regionProperties.defaultCode())).isEmpty();
        assertThat(regionRepository.findById(renamed).orElseThrow().getCode()).isEqualTo("LONDON");
    }

    @Test
    void anInstallationWithNoRegionAtAllStillGetsTheDefaultOne() {
        // The other arm, or the guard above would also pass on a seeder that seeds nothing ever.
        // Grants name region ids and the base rebuilds them for every test, so they go first.
        userRegionGrantRepository.deleteAll();
        regionRepository.deleteAll();

        dataSeeder.run();

        assertThat(regionRepository.findByCode(regionProperties.defaultCode()))
                .get()
                .satisfies(r -> {
                    assertThat(r.getName()).isEqualTo(regionProperties.defaultName());
                    assertThat(r.isActive()).isTrue();
                });
        assertThat(regionRepository.count()).isEqualTo(1);
    }
}
