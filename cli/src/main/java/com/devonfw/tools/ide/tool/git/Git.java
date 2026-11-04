package com.devonfw.tools.ide.tool.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.devonfw.tools.ide.common.Tag;
import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.process.ProcessContext;
import com.devonfw.tools.ide.process.ProcessMode;
import com.devonfw.tools.ide.tool.GlobalToolCommandlet;
import com.devonfw.tools.ide.tool.NativePackage;
import com.devonfw.tools.ide.tool.NativePackageManager;
import com.devonfw.tools.ide.tool.ToolInstallRequest;
import com.devonfw.tools.ide.tool.repository.ToolRepository;
import com.devonfw.tools.ide.version.VersionIdentifier;

/**
 * {@link GlobalToolCommandlet} for <a href="https://git-scm.com/">Git</a>.
 */
public class Git extends GlobalToolCommandlet {

  private ToolRepository gitForWindowsRepository;

  /**
   * The constructor.
   *
   * @param context the {@link IdeContext}.
   */
  public Git(IdeContext context) {
    super(context, "git", Set.of(Tag.GIT));
  }

  /**
   * @return {@code false} since Git is required to create a project and can therefore also be installed outside of a project.
   */
  @Override
  public boolean isIdeHomeRequired() {

    return false;
  }

  /**
   * On Windows without URL metadata for Git, the {@link GitForWindowsRepository} is used to install the latest version of Git, as Git itself may be required
   * to clone that metadata. On Linux and macOS no download is needed since Git is installed via the native package manager.
   *
   * @return the {@link ToolRepository}.
   */
  @Override
  public ToolRepository getToolRepository() {

    if (!isFallbackRequired()) {
      return super.getToolRepository();
    }
    if (this.gitForWindowsRepository == null) {
      this.gitForWindowsRepository = createGitForWindowsRepository();
    }
    return this.gitForWindowsRepository;
  }

  private boolean isFallbackRequired() {

    return this.context.getSystemInfo().isWindows() && !isUrlMetadataAvailable();
  }

  /**
   * Skips the check if the {@link GitForWindowsRepository} is used, as the CVE information is part of the URL metadata that does not exist in this case.
   */
  @Override
  protected VersionIdentifier cveCheck(ToolInstallRequest request) {

    if (isFallbackRequired()) {
      return request.getRequested().getResolvedVersion();
    }
    return super.cveCheck(request);
  }

  private boolean isUrlMetadataAvailable() {

    Path urlsPath = this.context.getUrlsPath();
    return (urlsPath != null) && Files.isDirectory(urlsPath.resolve(getName()));
  }

  /**
   * @return a new instance of {@link GitForWindowsRepository}.
   */
  protected ToolRepository createGitForWindowsRepository() {

    return new GitForWindowsRepository(this.context);
  }

  @Override
  protected String getBinaryName() {
    return "git";
  }

  @Override
  protected List<NativePackage> getNativePackages() {
    return List.of(
        NativePackage.of(NativePackageManager.APT, "git"),
        NativePackage.of(NativePackageManager.ZYPPER, "git"),
        NativePackage.of(NativePackageManager.BREW, "git"));
  }

  @Override
  public String getWindowsRegistryAppName() {
    return "Git";
  }

  /**
   * Runs the installer and waits for it instead of starting it in the background. Background processes are started via bash that may not exist yet and whose
   * path conversion (MSYS) would turn an argument like {@code /VERYSILENT} into {@code C:/Program Files/Git/VERYSILENT}.
   */
  @Override
  protected int runInstaller(ProcessContext pc) {

    return pc.addArgs(getInstallerArguments()).run(ProcessMode.DEFAULT).getExitCode();
  }

  /**
   * @return the arguments for the installer. The first installation on Windows shows the installation wizard, while upgrades and the batch mode are silent.
   */
  List<String> getInstallerArguments() {

    if (!this.context.getSystemInfo().isWindows()) {
      return List.of();
    }
    if ((getInstalledVersion() != null) || this.context.isBatchMode()) {
      return List.of("/VERYSILENT", "/NORESTART", "/NOCANCEL", "/SP-");
    }
    return List.of("/NORESTART", "/NOCANCEL", "/SP-");
  }

  /**
   * On macOS Git is installed via Homebrew (see {@link #getNativePackages()}) rather than via the DMG installer artifact, so the package manager based
   * installation flow is used there too - exactly like on Linux. On Windows the standard installer artifact is still used.
   *
   * @return {@code true} if Git is installed via the OS native package manager.
   */
  @Override
  protected boolean isPackageManagerBasedInstallation() {
    return this.context.getSystemInfo().isLinux() || this.context.getSystemInfo().isMac();
  }
}
