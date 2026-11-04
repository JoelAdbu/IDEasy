package com.devonfw.tools.ide.tool.git;

import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.devonfw.tools.ide.cli.CliException;
import com.devonfw.tools.ide.cli.CliOfflineException;
import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.os.SystemArchitecture;
import com.devonfw.tools.ide.os.SystemInfo;
import com.devonfw.tools.ide.tool.AbstractToolCommandlet;
import com.devonfw.tools.ide.tool.custom.CustomToolMetadata;
import com.devonfw.tools.ide.tool.repository.AbstractToolRepository;
import com.devonfw.tools.ide.url.model.file.UrlDownloadFileMetadata;
import com.devonfw.tools.ide.url.model.file.json.ToolDependency;
import com.devonfw.tools.ide.url.model.file.json.ToolSecurity;
import com.devonfw.tools.ide.version.VersionIdentifier;

/**
 * {@link com.devonfw.tools.ide.tool.repository.ToolRepository ToolRepository} for the latest release of Git for Windows. It is the fallback to install Git on a
 * machine without the URL metadata (ide-urls), since Git may be required to clone that metadata. For each {@link SystemArchitecture} Git for Windows publishes
 * a file with a fixed URL that contains the download URL of the latest installer. As that URL contains the actual version, the download can safely be cached.
 * That file contains no checksum, so the download is not verified by a checksum.
 */
public class GitForWindowsRepository extends AbstractToolRepository {

  /** {@link #getId() ID} of this repository. */
  public static final String ID = "git-for-windows";

  private static final Logger LOG = LoggerFactory.getLogger(GitForWindowsRepository.class);

  private static final String BASE_URL = "https://gitforwindows.org/";

  private CustomToolMetadata latestInstaller;

  /**
   * The constructor.
   *
   * @param context the owning {@link IdeContext}.
   */
  public GitForWindowsRepository(IdeContext context) {

    super(context);
  }

  @Override
  public String getId() {

    return ID;
  }

  /**
   * @return the base URL of the files pointing to the latest installer. May be overridden for testing.
   */
  protected String getBaseUrl() {

    return BASE_URL;
  }

  @Override
  public List<String> getSortedEditions(String tool) {

    return List.of(tool);
  }

  @Override
  public List<VersionIdentifier> getSortedVersions(String tool, String edition, AbstractToolCommandlet toolCommandlet) {

    return List.of(getLatestInstaller().getVersion());
  }

  @Override
  protected UrlDownloadFileMetadata getMetadata(String tool, String edition, VersionIdentifier version, AbstractToolCommandlet toolCommandlet) {

    CustomToolMetadata installer = getLatestInstaller();
    if (!installer.getVersion().equals(version)) {
      throw new CliException("Git version " + version + " is not available because only the latest version " + installer.getVersion()
          + " can be installed without the URL metadata.");
    }
    return installer;
  }

  @Override
  public Collection<ToolDependency> findDependencies(String tool, String edition, VersionIdentifier version) {

    return List.of();
  }

  @Override
  public ToolSecurity findSecurity(String tool, String edition) {

    return ToolSecurity.getEmpty();
  }

  private CustomToolMetadata getLatestInstaller() {

    if (this.latestInstaller == null) {
      this.latestInstaller = fetchLatestInstaller();
    }
    return this.latestInstaller;
  }

  private CustomToolMetadata fetchLatestInstaller() {

    SystemInfo systemInfo = this.context.getSystemInfo();
    if (!systemInfo.isWindows()) {
      throw new CliException("Git for Windows cannot be installed on " + systemInfo.getOs() + ".");
    }
    String architecture = getInstallerArchitecture(systemInfo.getArchitecture());
    String url = getBaseUrl() + "latest-" + architecture + "-installer.url";
    if (this.context.getNetworkStatus().isOffline()) {
      throw CliOfflineException.ofDownloadViaUrl(url);
    }
    String installerUrl = this.context.getFileAccess().download(url).trim();
    Pattern installerPattern = Pattern.compile(
        "https://github\\.com/git-for-windows/git/releases/download/[^/]+/Git-(\\d[^/]*)-" + Pattern.quote(architecture) + "\\.exe");
    Matcher matcher = installerPattern.matcher(installerUrl);
    if (!matcher.matches()) {
      throw new CliException("Received unexpected Git installer URL '" + installerUrl + "' from " + url + ".\n"
          + IdeContext.PLEASE_DOWNLOAD_AND_INSTALL_GIT + ":\n " + IdeContext.WINDOWS_GIT_DOWNLOAD_URL);
    }
    LOG.info("No URL metadata available yet, using latest Git installer from {} without checksum verification.", installerUrl);
    return new CustomToolMetadata("git", matcher.group(1), systemInfo.getOs(), systemInfo.getArchitecture(), installerUrl, null, getBaseUrl());
  }

  private static String getInstallerArchitecture(SystemArchitecture architecture) {

    return switch (architecture) {
      case X64 -> "64-bit";
      case ARM64 -> "arm64";
    };
  }

}
