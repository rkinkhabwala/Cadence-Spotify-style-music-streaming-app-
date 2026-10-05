package com.cadence;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails if any bounded context reaches into another context's internals (spec 3.1). */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(CadenceApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }
}
