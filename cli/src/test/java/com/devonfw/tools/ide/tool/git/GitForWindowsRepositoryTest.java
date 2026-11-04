package com.devonfw.tools.ide.tool.git;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import org.junit.jupiter.api.Test;

import com.devonfw.tools.ide.cli.CliException;
import com.devonfw.tools.ide.cli.CliOfflineException;
import com.devonfw.tools.ide.context.AbstractIdeContextTest;
import com.devonfw.tools.ide.context.IdeTestContext;
import com.devonfw.tools.ide.os.OperatingSystem;
import com.devonfw.tools.ide.os.SystemArchitecture;
import com.devonfw.tools.ide.os.SystemInfo;
import com.devonfw.tools.ide.os.SystemInfoMock;
import com.devonfw.tools.ide.url.model.file.UrlDownloadFileMetadata;
import com.devonfw.tools.ide.url.model.file.json.ToolSecurity;
import com.devonfw.tools.ide.version.VersionIdentifier;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * Test of {@link GitForWindowsRepository}.
 */
@WireMockTest
class GitForWindowsRepositoryTest extends AbstractIdeContextTest {

  private static final String RELEASES = "https://github.com/git-for-windows/git/releases/download/";

  private static final String URL_FILE_X64 = "/latest-64-bit-installer.url";

  private static final String URL_FILE_ARM64 = "/latest-arm64-installer.url";

  private static final String INSTALLER_X64 = RELEASES + "v2.56.0.windows.4/Git-2.56.0.4-64-bit.exe";

  private static final String INSTALLER_ARM64 = RELEASES + "v2.56.0.windows.4/Git-2.56.0.4-arm64.exe";

  private static final VersionIdentifier VERSION = VersionIdentifier.of("2.56.0.4");

  private static GitForWindowsRepository repository(WireMockRuntimeInfo wmRuntimeInfo, SystemInfo systemInfo) {

    IdeTestContext context = newContext(PROJECT_BASIC, wmRuntimeInfo);
    context.setSystemInfo(systemInfo);
    context.getNetworkStatus().simulateOnline();
    return repository(context, wmRuntimeInfo);
  }

  private static GitForWindowsRepository repository(IdeTestContext context, WireMockRuntimeInfo wmRuntimeInfo) {

    return new GitForWindowsRepository(context) {
      @Override
      protected String getBaseUrl() {
        return wmRuntimeInfo.getHttpBaseUrl() + "/";
      }
    };
  }

  private static void stubUrlFile(String path, String body) {

    stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(body)));
  }

  /**
   * Verifies that the latest version and installer are determined from the file for the x64 architecture including the trailing newline of that file.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testLatestInstallerOnWindowsX64(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    stubUrlFile(URL_FILE_X64, INSTALLER_X64 + "\n");
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_X64);

    // act
    VersionIdentifier version = repository.resolveVersion("git", "git", VersionIdentifier.LATEST, null);
    UrlDownloadFileMetadata metadata = repository.getMetadata("git", "git", version, null);

    // assert
    assertThat(version).isEqualTo(VERSION);
    assertThat(metadata.getUrls()).containsExactly(INSTALLER_X64);
    assertThat(metadata.getTool()).isEqualTo("git");
    assertThat(metadata.getEdition()).isEqualTo("git");
    assertThat(metadata.getVersion()).isEqualTo(VERSION);
    assertThat(metadata.getOs()).isEqualTo(OperatingSystem.WINDOWS);
    assertThat(metadata.getArch()).isEqualTo(SystemArchitecture.X64);
    assertThat(metadata.getChecksums()).isNull();
  }

  /**
   * Verifies that on ARM64 the ARM64 installer is used instead of the x64 installer.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testLatestInstallerOnWindowsArm64(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    stubUrlFile(URL_FILE_ARM64, INSTALLER_ARM64);
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_ARM64);

    // act
    UrlDownloadFileMetadata metadata = repository.getMetadata("git", "git", VERSION, null);

    // assert
    assertThat(metadata.getUrls()).containsExactly(INSTALLER_ARM64);
    assertThat(metadata.getArch()).isEqualTo(SystemArchitecture.ARM64);
  }

  /**
   * Verifies the version of the first Git for Windows release of a Git version, whose installer has no additional version segment.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testVersionOfFirstRelease(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    stubUrlFile(URL_FILE_X64, RELEASES + "v2.57.0.windows.1/Git-2.57.0-64-bit.exe");
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_X64);

    // act
    VersionIdentifier version = repository.resolveVersion("git", "git", VersionIdentifier.LATEST, null);

    // assert
    assertThat(version).isEqualTo(VersionIdentifier.of("2.57.0"));
  }

  /**
   * Verifies that the file with the latest installer is only requested once.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testLatestInstallerRequestedOnlyOnce(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    stubUrlFile(URL_FILE_X64, INSTALLER_X64);
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_X64);

    // act
    repository.resolveVersion("git", "git", VersionIdentifier.LATEST, null);
    repository.getMetadata("git", "git", VERSION, null);

    // assert
    verify(1, getRequestedFor(urlEqualTo(URL_FILE_X64)));
  }

  /**
   * Verifies that an executable is never downloaded from an unexpected location.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testUnexpectedInstallerUrlIsRejected(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    stubUrlFile(URL_FILE_X64, "https://example.com/Git-2.56.0.4-64-bit.exe");
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_X64);

    // act + assert
    assertThatThrownBy(() -> repository.resolveVersion("git", "git", VersionIdentifier.LATEST, null))
        .isInstanceOf(CliException.class)
        .hasMessageContaining("unexpected Git installer URL");
  }

  /**
   * Verifies that the installer of the wrong architecture is rejected.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testInstallerOfOtherArchitectureIsRejected(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    stubUrlFile(URL_FILE_ARM64, INSTALLER_X64);
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_ARM64);

    // act + assert
    assertThatThrownBy(() -> repository.resolveVersion("git", "git", VersionIdentifier.LATEST, null))
        .isInstanceOf(CliException.class)
        .hasMessageContaining("unexpected Git installer URL");
  }

  /**
   * Verifies that a version other than the latest one is rejected since only the latest installer is known.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testOtherVersionIsNotAvailable(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    stubUrlFile(URL_FILE_X64, INSTALLER_X64);
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_X64);
    VersionIdentifier otherVersion = VersionIdentifier.of("2.40.0");

    // act + assert
    assertThatThrownBy(() -> repository.resolveVersion("git", "git", otherVersion, null)).isInstanceOf(CliException.class);
    assertThatThrownBy(() -> repository.getMetadata("git", "git", otherVersion, null))
        .isInstanceOf(CliException.class)
        .hasMessageContaining("only the latest version 2.56.0.4");
  }

  /**
   * Verifies that a proper error is reported when offline.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testOffline(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC, wmRuntimeInfo);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    context.getNetworkStatus().simulateNetworkError();
    GitForWindowsRepository repository = repository(context, wmRuntimeInfo);

    // act + assert
    assertThatThrownBy(() -> repository.resolveVersion("git", "git", VersionIdentifier.LATEST, null)).isInstanceOf(CliOfflineException.class);
  }

  /**
   * Verifies that the repository refuses to work on other operating systems.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testNotWindows(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.LINUX_X64);

    // act + assert
    assertThatThrownBy(() -> repository.resolveVersion("git", "git", VersionIdentifier.LATEST, null)).isInstanceOf(CliException.class);
  }

  /**
   * Verifies that neither dependencies nor security information are read from the URL metadata that does not exist.
   *
   * @param wmRuntimeInfo wireMock server on a random port.
   */
  @Test
  void testNoDependenciesAndSecurityInformation(WireMockRuntimeInfo wmRuntimeInfo) {

    // arrange
    GitForWindowsRepository repository = repository(wmRuntimeInfo, SystemInfoMock.WINDOWS_X64);

    // act + assert
    assertThat(repository.findDependencies("git", "git", VERSION)).isEmpty();
    assertThat(repository.findSecurity("git", "git")).isSameAs(ToolSecurity.getEmpty());
    assertThat(repository.getSortedEditions("git")).containsExactly("git");
    assertThat(repository.getId()).isEqualTo("git-for-windows");
  }
}
