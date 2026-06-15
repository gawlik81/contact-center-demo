package com.contactcenter.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * Loguje informacje o wersji aplikacji przy starcie.
 *
 * <p>Źródła danych:
 * <ul>
 *   <li>{@link GitProperties} – generowane przez {@code git-commit-id-maven-plugin}
 *       (plik {@code git.properties}), zawiera m.in. {@code git.commit.id.describe}
 *       (np. {@code v1.3.0-7-gabc1234} lub {@code v1.3.0-7-gabc1234-dirty}), branch,
 *       short commit id.</li>
 *   <li>{@link BuildProperties} – generowane przez goal {@code build-info} pluginu
 *       {@code spring-boot-maven-plugin} (plik {@code META-INF/build-info.properties}),
 *       zawiera wersję z POM jako fallback.</li>
 * </ul>
 *
 * <p>Oba beany są opcjonalne – jeśli pliki nie zostały wygenerowane (np. build bez
 * kontekstu {@code .git}), aplikacja loguje {@code version=unknown} bez błędu.
 */
@Slf4j
@Component
public class StartupInfoLogger {

  private final Optional<GitProperties> gitProperties;
  private final Optional<BuildProperties> buildProperties;
  private final Environment environment;

  public StartupInfoLogger(
      Optional<GitProperties> gitProperties,
      Optional<BuildProperties> buildProperties,
      Environment environment) {
    this.gitProperties = gitProperties;
    this.buildProperties = buildProperties;
    this.environment = environment;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void logStartupInfo() {
    String version = resolveVersion();
    String branch = gitProperties.map(GitProperties::getBranch).orElse("unknown");
    String commit = gitProperties.map(GitProperties::getShortCommitId).orElse("unknown");
    String profiles = resolveActiveProfiles();

    log.info(
        "Contact Center Backend started | version={} | branch={} | commit={} | profiles={}",
        version,
        branch,
        commit,
        profiles);
  }

  private String resolveVersion() {
    return gitProperties
        .map(props -> props.get("git.commit.id.describe"))
        .filter(describe -> describe != null && !describe.isBlank())
        .or(() -> buildProperties.map(BuildProperties::getVersion))
        .orElse("unknown");
  }

  private String resolveActiveProfiles() {
    String[] activeProfiles = environment.getActiveProfiles();
    String[] profiles = activeProfiles.length > 0 ? activeProfiles : environment.getDefaultProfiles();
    return Arrays.toString(profiles);
  }
}
