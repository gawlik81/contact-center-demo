package com.contactcenter.infrastructure.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-171/BE-172 guard: EVERY {@code @Scheduled} method in the application either carries a valid
 * {@code @SchedulerLock} or is a documented exception, and the classification table in
 * {@code documentation/tech/04-backend.md} lists every one of them. A new scheduled job added without a lock
 * decision (S/I/T) fails this test.
 */
@DisplayName("Każda metoda @Scheduled ma blokadę ShedLock albo udokumentowany wyjątek (BE-171/BE-172)")
class SchedulerLockCoverageTest {

    /**
     * Scheduled methods intentionally WITHOUT {@code @SchedulerLock}. Key = {@code SimpleClassName#method}.
     * Adding an entry here requires a matching row in the classification table (class T or programmatic lock).
     */
    private static final Map<String, String> UNLOCKED_BY_DESIGN = Map.of(
            "EmailPollingServiceImpl#pollAllTenants",
            "S, programmatic per-mailbox lock (LockingTaskExecutor, EmailPolling_pollInbox_<tenantId>)",
            "ProgressiveDialerServiceImpl#pollAvailableAgents",
            "T, per-campaign advisory xact lock in the claim transaction (BE-173, done)",
            "RoutingServiceImpl#pollAvailableAgents",
            "T, per-queue ShedLock RoutingQueue_<queueId> inside the pass (BE-173, done)");

    private static List<Method> scheduledMethods() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;   // every concrete class, not only stereotype-annotated ones
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile("com\\.contactcenter\\..*")));

        List<Method> methods = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.contactcenter")) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false,
                    SchedulerLockCoverageTest.class.getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static String key(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    /** Resolves {@code ${prop:default}} to its default (no properties set) and parses ISO-8601 or plain millis. */
    private static Duration duration(String raw) {
        return duration(raw, new StandardEnvironment());
    }

    private static Duration duration(String raw, StandardEnvironment env) {
        String resolved = env.resolvePlaceholders(raw);
        return resolved.startsWith("P") ? Duration.parse(resolved) : Duration.ofMillis(Long.parseLong(resolved));
    }

    /** Environment as the application sees it for a profile: application.yml, overridden by application-{profile}.yml. */
    private static StandardEnvironment environment(String profile) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (profile != null) {
            for (PropertySource<?> ps : loader.load("application-" + profile,
                    new ClassPathResource("application-" + profile + ".yml"))) {
                env.getPropertySources().addLast(ps);
            }
        }
        for (PropertySource<?> ps : loader.load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(ps);
        }
        return env;
    }

    /** Interval (ms) of a fixedDelay/fixedRate job resolved against the env; empty for cron jobs. */
    private static java.util.OptionalLong intervalMs(Method method, StandardEnvironment env) {
        Scheduled sc = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class);
        long v = sc.fixedDelay() >= 0 ? sc.fixedDelay() : sc.fixedRate();
        if (v >= 0) {
            return java.util.OptionalLong.of(v);
        }
        String str = !sc.fixedDelayString().isEmpty() ? sc.fixedDelayString() : sc.fixedRateString();
        return str.isEmpty() ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(Long.parseLong(env.resolvePlaceholders(str)));
    }

    @Test
    @DisplayName("każda klasa z @SchedulerLock da się opakować w proxy CGLIB, a metoda jest przechwytywalna (nie final/private/static)")
    void everyLockedJobIsCglibProxyable() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Method method : scheduledMethods()) {
            if (AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class) == null) {
                continue;
            }
            Class<?> type = method.getDeclaringClass();
            int mod = method.getModifiers();
            if (java.lang.reflect.Modifier.isFinal(type.getModifiers()) || java.lang.reflect.Modifier.isFinal(mod)
                    || java.lang.reflect.Modifier.isPrivate(mod) || java.lang.reflect.Modifier.isStatic(mod)) {
                problems.add(key(method) + ": class/method final, private or static - ShedLock proxy cannot intercept it");
                continue;
            }
            try {
                org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory();
                factory.setTargetClass(type);
                factory.setProxyTargetClass(true);
                factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> null);
                Object proxy = factory.getProxy(type.getClassLoader());
                proxy.getClass().getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (Exception | LinkageError e) {
                problems.add(key(method) + ": CGLIB proxy not creatable/overriding (" + e + ")");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("lockAtLeastFor < interwał fixedDelay/fixedRate - dla domyślnych wartości w profilach: bazowy, dev, prod")
    void lockAtLeastForIsBelowTheInterval() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String profile : new String[]{null, "dev", "prod"}) {
            StandardEnvironment env = environment(profile);
            for (Method method : scheduledMethods()) {
                SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
                java.util.OptionalLong interval = intervalMs(method, env);
                if (lock == null || interval.isEmpty()) {
                    continue;
                }
                Duration atLeast = duration(lock.lockAtLeastFor(), env);
                if (atLeast.toMillis() >= interval.getAsLong()) {
                    problems.add(key(method) + " [" + (profile == null ? "base" : profile) + "]: lockAtLeastFor "
                            + atLeast + " >= interval " + interval.getAsLong() + " ms");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("metoda @Scheduled bez wyjątku ma @SchedulerLock: nazwa <Klasa>_<metoda> (<= 64), unikalna, lockAtLeastFor <= lockAtMostFor")
    void everyScheduledMethodIsLockedOrDocumentedException() throws Exception {
        List<Method> scheduled = scheduledMethods();
        assertThat(scheduled).as("scanner found the @Scheduled methods").hasSizeGreaterThanOrEqualTo(20);

        Set<String> names = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (Method method : scheduled) {
            SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
            String key = key(method);
            if (lock == null) {
                if (!UNLOCKED_BY_DESIGN.containsKey(key)) {
                    problems.add(key + " has @Scheduled without @SchedulerLock and is not a documented exception");
                }
                continue;
            }
            if (UNLOCKED_BY_DESIGN.containsKey(key)) {
                problems.add(key + " is on the exception list but has @SchedulerLock");
            }
            String expectedName = method.getDeclaringClass().getSimpleName() + "_" + method.getName();
            if (!expectedName.equals(lock.name())) {
                problems.add(key + ": lock name '" + lock.name() + "' != convention '" + expectedName + "'");
            }
            if (lock.name().length() > 64) {
                problems.add(key + ": lock name longer than 64 chars (shedlock.name is VARCHAR(64))");
            }
            if (!names.add(lock.name())) {
                problems.add(key + ": duplicate lock name " + lock.name());
            }
            try {
                Duration atMost = duration(lock.lockAtMostFor());
                Duration atLeast = duration(lock.lockAtLeastFor());
                if (atMost.isZero() || atLeast.compareTo(atMost) > 0) {
                    problems.add(key + ": lockAtLeastFor " + atLeast + " must be <= lockAtMostFor " + atMost
                            + " (and lockAtMostFor > 0)");
                }
            } catch (RuntimeException e) {
                problems.add(key + ": unparsable lock duration (" + e.getMessage() + ")");
            }
        }
        assertThat(problems).isEmpty();
        UNLOCKED_BY_DESIGN.keySet().forEach(k -> assertThat(scheduled.stream().map(SchedulerLockCoverageTest::key))
                .as("exception list entry still exists in code: " + k).contains(k));
    }

    @Test
    @DisplayName("tabela klasyfikacji w documentation/tech/04-backend.md wymienia każdą metodę @Scheduled (100%)")
    void classificationTableCoversEveryScheduledMethod() throws Exception {
        Path doc = findDoc();
        assertThat(doc).as("documentation/tech/04-backend.md must be reachable from the test working directory").isNotNull();
        List<String> tableRows = Files.readAllLines(doc).stream().filter(l -> l.startsWith("|")).toList();

        Set<String> missing = new TreeSet<>();
        for (Method method : scheduledMethods()) {
            if (tableRows.stream().noneMatch(row -> row.contains("`" + key(method) + "`"))) {
                missing.add(key(method));
            }
        }
        assertThat(missing).as("scheduled methods missing from the classification table").isEmpty();
    }

    private static Path findDoc() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("documentation/tech/04-backend.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
