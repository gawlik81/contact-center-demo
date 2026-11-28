package com.contactcenter.domain.plugin.runtime;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-244 N3: a nonsensical {@code plugins.sync.*} value must fail the startup binding, not degrade silently. */
@DisplayName("PluginSyncProperties - walidacja (BE-244 N3)")
class PluginSyncPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("wartosci domyslne sa poprawne")
    void defaultsAreValid() {
        assertThat(validator.validate(new PluginSyncProperties())).isEmpty();
    }

    @Test
    @DisplayName("rate <= 0 (tokeny nigdy sie nie odnawiaja) jest odrzucony")
    void nonPositiveRateIsRejected() {
        PluginSyncProperties p = new PluginSyncProperties();
        p.setAssetLazyLoadRatePerSecond(0.0);
        assertThat(validator.validate(p)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("assetLazyLoadRatePerSecond");

        p.setAssetLazyLoadRatePerSecond(-1.0);
        assertThat(validator.validate(p)).hasSize(1);
    }

    @Test
    @DisplayName("burst, watki, pojemnosci kolejek i timeouty musza byc dodatnie")
    void nonPositiveSizesAreRejected() {
        PluginSyncProperties p = new PluginSyncProperties();
        p.setAssetLazyLoadRateBurst(0);
        p.setLoadThreads(0);
        p.setSyncThreads(-1);
        p.setLoadQueueCapacity(0);
        p.setSyncQueueCapacity(0);
        p.setAssetLazyLoadThreads(0);
        p.setAssetLazyLoadQueueCapacity(0);
        p.setAssetLazyLoadTimeoutMs(0);
        p.setAssetLazyLoadMaxInflight(0);
        p.setAssetLazyLoadMaxWaiters(0);
        p.setReconcileIntervalMs(0);

        assertThat(validator.validate(p)).extracting(v -> v.getPropertyPath().toString())
                .contains("assetLazyLoadRateBurst", "loadThreads", "syncThreads", "loadQueueCapacity",
                        "syncQueueCapacity", "assetLazyLoadThreads", "assetLazyLoadQueueCapacity",
                        "assetLazyLoadTimeoutMs", "assetLazyLoadMaxInflight", "assetLazyLoadMaxWaiters",
                        "reconcileIntervalMs");
    }
}
