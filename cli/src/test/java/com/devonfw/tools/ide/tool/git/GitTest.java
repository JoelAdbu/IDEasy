package com.devonfw.tools.ide.tool.git;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.devonfw.tools.ide.context.AbstractIdeContextTest;
import com.devonfw.tools.ide.context.IdeTestContext;
import com.devonfw.tools.ide.git.GitContext;
import com.devonfw.tools.ide.git.GitContextImpl;
import com.devonfw.tools.ide.io.FileAccess;
import com.devonfw.tools.ide.log.IdeLogLevel;
import com.devonfw.tools.ide.os.SystemInfoMock;
import com.devonfw.tools.ide.os.WindowsAppInstallation;
import com.devonfw.tools.ide.os.WindowsHelperMock;
import com.devonfw.tools.ide.process.OutputMessage;
import com.devonfw.tools.ide.process.ProcessContext;
import com.devonfw.tools.ide.process.ProcessMode;
import com.devonfw.tools.ide.process.ProcessResult;
import com.devonfw.tools.ide.process.ProcessResultImpl;
import com.devonfw.tools.ide.tool.EditionAndVersion;
import com.devonfw.tools.ide.tool.NativePackageManager;
import com.devonfw.tools.ide.tool.ToolEditionAndVersion;
import com.devonfw.tools.ide.tool.ToolInstallRequest;
import com.devonfw.tools.ide.tool.ToolInstallation;
import com.devonfw.tools.ide.tool.repository.ToolRepository;
import com.devonfw.tools.ide.url.model.file.json.ToolSecurity;
import com.devonfw.tools.ide.version.VersionIdentifier;

/**
 * Test of {@link Git}.
 */
class GitTest extends AbstractIdeContextTest {

  private static final String TOOL_VERSION = "2.45.0";

  /** The version query command the package manager based version detection issues on macOS (see {@link NativePackageManager#BREW}). */
  private static final String BREW_VERSION_QUERY = "brew list --versions git";

  /** The version query command the package manager based version detection issues on Linux (see {@link NativePackageManager#APT}). */
  private static final String APT_VERSION_QUERY = "dpkg-query -W -f=${db:Status-Status}|${Version} git";

  /**
   * A {@link Git} that reports only the given native package managers as available, so a specific OS flow (apt on Linux, brew on macOS) is exercised.
   *
   * @param context the {@link IdeTestContext}.
   * @param availablePackageManagers the package managers {@code isPackageManagerAvailable} should report as available.
   * @return the {@link Git}.
   */
  private static Git git(IdeTestContext context, NativePackageManager... availablePackageManagers) {

    return new Git(context) {
      @Override
      protected boolean isPackageManagerAvailable(NativePackageManager packageManager) {
        return Arrays.asList(availablePackageManagers).contains(packageManager);
      }
    };
  }

  /**
   * Returns a mocked {@link ProcessContext} (as returned by {@code newProcess()}) whose version query yields the given single-line output, so that the package
   * manager based version detection can be simulated without running the real package manager.
   *
   * @param context the {@link IdeTestContext}.
   * @param command the command whose output should be simulated (for readability of the returned result).
   * @param output the single output line the version query should yield, or {@code null} to simulate that nothing is installed.
   * @return the mocked {@link ProcessContext}.
   */
  private ProcessContext processContextWithVersionOutput(IdeTestContext context, String command, String output) {

    ProcessContext processContext = Mockito.mock(ProcessContext.class, Mockito.RETURNS_SELF);
    List<OutputMessage> outputMessages = (output == null) ? List.of() : List.of(new OutputMessage(false, output));
    ProcessResult result = new ProcessResultImpl("bash", command, ProcessResult.SUCCESS, true, outputMessages);
    Mockito.when(processContext.run(ProcessMode.DEFAULT_CAPTURE)).thenReturn(result);
    context.setProcessContext(processContext);
    return processContext;
  }

  /**
   * Returns a fresh, mutable {@link IdeTestContext} (Windows x64, no git, no local URL metadata) suitable for testing the fallback for missing URL metadata.
   *
   * @return the {@link IdeTestContext}.
   */
  private static IdeTestContext freshWindowsContext() {

    IdeTestContext context = newContext(PROJECT_BASIC, null, true);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    context.getFileAccess().delete(context.getUrlsPath()); // simulate the fresh machine without local URL metadata
    return context;
  }

  /**
   * Returns an {@link IdeTestContext} (Windows x64) where neither git nor bash can be found, independent of the PATH of the machine running the test.
   *
   * @return the {@link IdeTestContext}.
   */
  private static IdeTestContext windowsContextWithoutGitInPath() {

    IdeTestContext supportContext = newContext("find-bash-git", "project/workspaces", true);
    Path home = supportContext.getUserHome();
    FileAccess fileAccess = supportContext.getFileAccess();
    Path environmentFile = home.resolve("environment.properties");
    Properties properties = fileAccess.readProperties(environmentFile);
    properties.put("PATH", home.resolve("notexisting").toAbsolutePath().toString());
    properties.remove("BASH_PATH");
    fileAccess.writeProperties(properties, environmentFile);
    IdeTestContext context = new IdeTestContext(home, IdeLogLevel.DEBUG, null);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    return context;
  }

  /**
   * Verifies that Git declares a Homebrew formula as one of its native packages, so that on macOS it can be installed via {@code brew} the same way it is
   * installed via {@code apt}/{@code zypper} on Linux.
   */
  @Test
  void testGetNativePackagesContainsBrew() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    Git git = new Git(context);

    // act + assert
    assertThat(git.getNativePackages().stream().map(pkg -> pkg.getPackageManager())).contains(NativePackageManager.BREW);
  }

  /**
   * Verifies the binary name of Git.
   */
  @Test
  void testGetBinaryName() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    Git git = new Git(context);

    // act + assert
    assertThat(git.getBinaryName()).isEqualTo("git");
  }

  /**
   * Verifies that the Windows registry app name of Git is {@code "Git"} (which differs from the lower case tool name).
   */
  @Test
  void testGetWindowsRegistryAppName() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    Git git = new Git(context);

    // act + assert
    assertThat(git.getWindowsRegistryAppName()).isEqualTo("Git");
    assertThat(git.getWindowsRegistryAppNames()).containsEntry("git", "Git");
  }

  /**
   * Verifies that the installed Git edition and version are resolved from Homebrew ({@code brew list --versions git}) on macOS, mirroring the Linux flow.
   */
  @Test
  void testGetInstalledEditionAndVersionOnMac() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.MAC_X64);
    processContextWithVersionOutput(context, BREW_VERSION_QUERY, "git " + TOOL_VERSION);
    Git git = git(context, NativePackageManager.BREW);

    // act
    EditionAndVersion editionAndVersion = git.getInstalledEditionAndVersion();

    // assert
    assertThat(editionAndVersion).isNotNull();
    assertThat(editionAndVersion.edition()).isEqualTo("git");
    assertThat(editionAndVersion.version()).isEqualTo(VersionIdentifier.of(TOOL_VERSION));
  }

  /**
   * Verifies that on macOS the installed Git version is {@code null} when no git formula is installed.
   */
  @Test
  void testGetInstalledEditionAndVersionOnMacWhenNotInstalled() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.MAC_X64);
    processContextWithVersionOutput(context, BREW_VERSION_QUERY, null);
    Git git = git(context, NativePackageManager.BREW);

    // act + assert
    assertThat(git.getInstalledEditionAndVersion()).isNull();
    assertThat(git.getInstalledVersion()).isNull();
  }

  /**
   * Verifies that the installed Git edition and version are resolved from apt ({@code dpkg-query}) on Linux.
   */
  @Test
  void testGetInstalledEditionAndVersionOnLinux() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.LINUX_X64);
    processContextWithVersionOutput(context, APT_VERSION_QUERY, "installed|" + TOOL_VERSION);
    Git git = git(context, NativePackageManager.APT);

    // act
    EditionAndVersion editionAndVersion = git.getInstalledEditionAndVersion();

    // assert
    assertThat(editionAndVersion).isNotNull();
    assertThat(editionAndVersion.edition()).isEqualTo("git");
    assertThat(editionAndVersion.version()).isEqualTo(VersionIdentifier.of(TOOL_VERSION));
  }

  /**
   * Verifies that on Linux the installed Git version is {@code null} when git is not installed (the apt version query yields no installed version).
   */
  @Test
  void testGetInstalledEditionAndVersionOnLinuxWhenNotInstalled() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.LINUX_X64);
    processContextWithVersionOutput(context, APT_VERSION_QUERY, null);
    Git git = git(context, NativePackageManager.APT);

    // act + assert
    assertThat(git.getInstalledEditionAndVersion()).isNull();
    assertThat(git.getInstalledVersion()).isNull();
  }

  /**
   * Verifies that on Windows the installed Git edition and version are resolved from the Windows registry using the app name {@code Git}.
   */
  @Test
  void testGetInstalledEditionAndVersionOnWindows() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    WindowsHelperMock helper = (WindowsHelperMock) context.getWindowsHelper();
    helper.setAppInstallationFromRegistry("Git", new WindowsAppInstallation(TOOL_VERSION, null, null, null));
    Git git = new Git(context);

    // act
    EditionAndVersion editionAndVersion = git.getInstalledEditionAndVersion();

    // assert
    assertThat(editionAndVersion).isNotNull();
    assertThat(editionAndVersion.edition()).isEqualTo("git");
    assertThat(editionAndVersion.version()).isEqualTo(VersionIdentifier.of(TOOL_VERSION));
  }

  /**
   * Verifies that Git can be installed outside of a project (see #2273).
   */
  @Test
  void testIdeHomeNotRequired() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);

    // act + assert
    assertThat(new Git(context).isIdeHomeRequired()).isFalse();
  }

  /**
   * Verifies that the very first installation of Git on Windows (Git is not installed yet) runs the installer interactively so the user can follow the
   * installation wizard.
   */
  @Test
  void testGetInstallerArgumentsInteractiveOnFirstInstallation() {

    // arrange - Windows, Git not installed yet
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    Git git = new Git(context);

    // act
    List<String> gitArguments = git.getInstallerArguments();

    // assert
    assertThat(gitArguments).containsExactly("/NORESTART", "/NOCANCEL", "/SP-");
  }

  /**
   * Verifies that a Git upgrade on Windows (Git already installed) runs the installer fully silent via {@code /VERYSILENT}.
   */
  @Test
  void testGetInstallerArgumentsSilentOnUpgrade() {

    // arrange - Windows, Git already installed
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    WindowsHelperMock helper = (WindowsHelperMock) context.getWindowsHelper();
    helper.setAppInstallationFromRegistry("Git", new WindowsAppInstallation(TOOL_VERSION, null, null, null));
    Git git = new Git(context);

    // act
    List<String> gitArguments = git.getInstallerArguments();

    // assert - fully silent upgrade
    assertThat(gitArguments).containsExactly("/VERYSILENT", "/NORESTART", "/NOCANCEL", "/SP-");
  }

  /**
   * Verifies that the first installation is silent in batch mode, since nobody can operate the installation wizard.
   */
  @Test
  void testGetInstallerArgumentsSilentInBatchMode() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    context.getStartContext().setBatchMode(true);
    Git git = new Git(context);

    // act
    List<String> gitArguments = git.getInstallerArguments();

    // assert
    assertThat(gitArguments).containsExactly("/VERYSILENT", "/NORESTART", "/NOCANCEL", "/SP-");
  }

  /**
   * Verifies that on macOS and Linux Git is installed via the package manager, so no installer arguments are produced.
   */
  @Test
  void testGetInstallerArgumentsEmptyOnMacAndLinux() {

    // arrange
    IdeTestContext macContext = newContext(PROJECT_BASIC);
    macContext.setSystemInfo(SystemInfoMock.MAC_X64);

    IdeTestContext linuxContext = newContext(PROJECT_BASIC);
    linuxContext.setSystemInfo(SystemInfoMock.LINUX_X64);

    //act
    List<String> macInstallerArguments = new Git(macContext).getInstallerArguments();
    List<String> linuxInstallerArguments = new Git(linuxContext).getInstallerArguments();

    //assert
    assertThat(macInstallerArguments).isEmpty();
    assertThat(linuxInstallerArguments).isEmpty();
  }

  /**
   * Verifies that on a fresh Windows machine where neither Git nor the URL metadata are available, the {@link GitForWindowsRepository} is used as fallback.
   */
  @Test
  void testGetToolRepositoryOnFreshWindowsMachine() {

    // arrange
    IdeTestContext context = freshWindowsContext();

    // act + assert
    assertThat(new Git(context).getToolRepository()).isInstanceOf(GitForWindowsRepository.class);
  }

  /**
   * Verifies that the fallback is also used if the URL metadata folder exists but does not contain Git, as otherwise Git could not be resolved.
   */
  @Test
  void testGetToolRepositoryWithoutUrlMetadataForGit() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC, null, true);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    assertThat(context.getUrlsPath()).isDirectory();

    // act + assert
    assertThat(new Git(context).getToolRepository()).isInstanceOf(GitForWindowsRepository.class);
  }

  /**
   * Verifies that on a fresh Windows machine where neither Git nor a Bash (Git Bash) installation is available yet, {@link GitContextImpl#findGit()} reports
   * "Git not found" by returning {@code null} instead of failing.
   */
  @Test
  void testFindGitReturnsNullOnFreshWindowsMachineWithoutGitAndBash() {

    // arrange
    IdeTestContext context = windowsContextWithoutGitInPath();
    GitContext gitContext = new GitContextImpl(context);

    // act
    Path gitPath = gitContext.findGit();

    // assert
    assertThat(gitPath).as("findGit() must return null (not throw) when neither git nor bash is available").isNull();
  }

  /**
   * Verifies that the decision for the fallback only depends on the URL metadata, so looking for Git (and bash) is avoided on a fresh machine.
   */
  @Test
  void testGetToolRepositoryDoesNotLookForGit() {

    // arrange
    IdeTestContext context = freshWindowsContext();
    GitContext gitContext = Mockito.mock(GitContext.class);
    context.setGitContext(gitContext);

    // act + assert
    assertThat(new Git(context).getToolRepository()).isInstanceOf(GitForWindowsRepository.class);
    Mockito.verifyNoInteractions(gitContext);
  }

  /**
   * Verifies that the regular {@link ToolRepository} is used without looking for Git when the URL metadata for Git already exists.
   */
  @Test
  void testGetToolRepositoryWhenUrlMetadataExists() {

    // arrange
    IdeTestContext context = freshWindowsContext();
    context.getFileAccess().mkdirs(context.getUrlsPath().resolve("git"));
    GitContext gitContext = Mockito.mock(GitContext.class);
    context.setGitContext(gitContext);

    // act + assert
    assertThat(new Git(context).getToolRepository()).isSameAs(context.getDefaultToolRepository());
    Mockito.verifyNoInteractions(gitContext);
  }

  /**
   * Verifies that the fallback is not used on Linux and macOS where Git is installed via the native package manager.
   */
  @Test
  void testGetToolRepositoryOnNonWindows() {

    // arrange
    IdeTestContext macContext = freshWindowsContext();
    macContext.setSystemInfo(SystemInfoMock.MAC_X64);
    IdeTestContext linuxContext = freshWindowsContext();
    linuxContext.setSystemInfo(SystemInfoMock.LINUX_X64);

    // act + assert
    assertThat(new Git(macContext).getToolRepository()).isSameAs(macContext.getDefaultToolRepository());
    assertThat(new Git(linuxContext).getToolRepository()).isSameAs(linuxContext.getDefaultToolRepository());
  }

  /**
   * Verifies the entire installation on a fresh Windows machine: the installer of the latest version comes from the fallback repository, it is started
   * interactively without bash (not in the background) and the regular repository (that would require the URL metadata and hence Git) is never accessed.
   */
  @Test
  void testInstallOnFreshWindowsMachine() {

    // arrange
    IdeTestContext context = windowsContextWithoutGitInPath();
    ToolRepository defaultRepository = Mockito.mock(ToolRepository.class);
    context.setDefaultToolRepository(defaultRepository);
    VersionIdentifier latestVersion = VersionIdentifier.of("2.56.0.4");
    Path installer = context.getUserHome().resolve("git-installer.exe");
    ToolRepository fallbackRepository = Mockito.mock(ToolRepository.class);
    Mockito.when(fallbackRepository.resolveVersion(Mockito.eq("git"), Mockito.eq("git"), Mockito.any(), Mockito.any())).thenReturn(latestVersion);
    Mockito.when(fallbackRepository.findSecurity("git", "git")).thenReturn(ToolSecurity.getEmpty());
    Mockito.when(fallbackRepository.download(Mockito.eq("git"), Mockito.eq("git"), Mockito.eq(latestVersion), Mockito.any())).thenReturn(installer);
    ProcessContext processContext = Mockito.mock(ProcessContext.class, Mockito.RETURNS_SELF);
    Mockito.when(processContext.run(ProcessMode.DEFAULT))
        .thenReturn(new ProcessResultImpl("installer", "installer", ProcessResult.SUCCESS, true, List.of()));
    context.setProcessContext(processContext);
    Git git = new Git(context) {
      @Override
      protected ToolRepository createGitForWindowsRepository() {
        return fallbackRepository;
      }
    };
    ToolInstallRequest request = ToolInstallRequest.ofDirect();
    request.setRequested(new ToolEditionAndVersion(VersionIdentifier.LATEST));

    // act
    ToolInstallation installation = git.install(request);

    // assert
    assertThat(installation.resolvedVersion()).isEqualTo(latestVersion);
    Mockito.verify(processContext).executable(installer);
    Mockito.verify(processContext).addArgs(List.of("/NORESTART", "/NOCANCEL", "/SP-"));
    Mockito.verifyNoInteractions(defaultRepository);
  }
}
