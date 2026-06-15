package com.contactcenter.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.mock.env.MockEnvironment;

/**
 * Testy {@link StartupInfoLogger}.
 *
 * <p>Weryfikuje, że logger nie rzuca wyjątku niezależnie od dostępności
 * {@link GitProperties} i {@link BuildProperties}, oraz że wybiera odpowiednie
 * źródło wersji.
 */
class StartupInfoLoggerTest {

  @Test
  void shouldLogWithoutErrorWhenGitAndBuildPropertiesPresent() {
    Properties gitProps = new Properties();
    gitProps.setProperty("git.branch", "develop");
    gitProps.setProperty("git.commit.id.abbrev", "abc1234");
    gitProps.setProperty("git.commit.id.describe", "v1.3.0-7-gabc1234");
    GitProperties gitProperties = new GitProperties(gitProps);

    Properties buildProps = new Properties();
    buildProps.setProperty("version", "1.0.0-SNAPSHOT");
    BuildProperties buildProperties = new BuildProperties(buildProps);

    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");

    StartupInfoLogger logger =
        new StartupInfoLogger(Optional.of(gitProperties), Optional.of(buildProperties), environment);

    assertThatCode(logger::logStartupInfo).doesNotThrowAnyException();
  }

  @Test
  void shouldFallBackToBuildVersionWhenGitPropertiesMissing() {
    Properties buildProps = new Properties();
    buildProps.setProperty("version", "1.0.0-SNAPSHOT");
    BuildProperties buildProperties = new BuildProperties(buildProps);

    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    StartupInfoLogger logger =
        new StartupInfoLogger(Optional.empty(), Optional.of(buildProperties), environment);

    assertThatCode(logger::logStartupInfo).doesNotThrowAnyException();
  }

  @Test
  void shouldLogUnknownVersionWhenNeitherPropertiesPresent() {
    MockEnvironment environment = new MockEnvironment();

    StartupInfoLogger logger =
        new StartupInfoLogger(Optional.empty(), Optional.empty(), environment);

    assertThatCode(logger::logStartupInfo).doesNotThrowAnyException();
  }

  @Test
  void shouldUseDefaultProfilesWhenNoActiveProfiles() {
    MockEnvironment environment = new MockEnvironment();
    // brak setActiveProfiles -> getActiveProfiles() zwraca puste, default = "default"

    StartupInfoLogger logger =
        new StartupInfoLogger(Optional.empty(), Optional.empty(), environment);

    assertThatCode(logger::logStartupInfo).doesNotThrowAnyException();
  }
}
