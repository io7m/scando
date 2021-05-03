/*
 * Copyright © 2020 Mark Raynsford <code@io7m.com> http://io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.io7m.scando.cmdline;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.JApiClass;
import japicmp.output.semver.SemverOut;
import japicmp.output.stdout.StdoutOutputGenerator;
import japicmp.output.xml.XmlOutput;
import japicmp.output.xml.XmlOutputGenerator;
import japicmp.output.xml.XmlOutputGeneratorOptions;
import japicmp.util.Optional;
import japicmp.versioning.SemanticVersion;
import japicmp.versioning.Version;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * The main command-line entry point.
 */

public final class Main
{
  private Main()
  {

  }

  /**
   * The main parameters.
   */

  public static final class Parameters
  {
    Parameters()
    {

    }

    @Parameter(
      names = "--help",
      description = "Display this help message",
      help = true
    )
    private boolean help;

    @Parameter(
      names = "--oldJarUri",
      description = "The old jar/aar file/URI",
      required = true
    )
    private URI oldJarUri;

    @Parameter(
      names = "--oldJarVersion",
      description = "The old jar/aar version",
      required = true
    )
    private String oldJarVersion;

    @Parameter(
      names = "--newJar",
      description = "The new jar/aar file",
      required = true
    )
    private Path newJarPath;

    @Parameter(
      names = "--newJarVersion",
      description = "The new jar/aar version",
      required = true
    )
    private String newJarVersion;

    @Parameter(
      names = "--textReport",
      description = "The output file for the plain text report",
      required = true
    )
    private Path textReport;

    @Parameter(
      names = "--htmlReport",
      description = "The output file for the HTML report",
      required = true
    )
    private Path htmlReport;

    @Parameter(
      names = "--excludeList",
      description = "A file containing a list of package/class exclusions",
      required = false
    )
    private Path excludeList;

    @Parameter(
      names = "--ignoreMissingOld",
      description = "Trivially succeed if the old jar is missing.",
      required = false
    )
    private boolean ignoreMissingOld;

    private Path oldJarPath;
  }

  private static SemanticVersion semanticVersionOf(
    final Version version)
  {
    final Optional<SemanticVersion> versionOpt = version.getSemanticVersion();
    if (versionOpt.isPresent()) {
      return versionOpt.get();
    }
    throw new IllegalArgumentException(String.format(
      "Version %s cannot be parsed as a semantic version",
      version)
    );
  }

  /**
   * The main exitless entry point. Raises exceptions on errors.
   *
   * @param args The command-line arguments
   *
   * @return The program exit code
   *
   * @throws Exception On errors
   */

  public static int mainExitless(
    final String[] args)
    throws Exception
  {
    final Parameters parameters = new Parameters();
    try {
      final JCommander commander =
        JCommander.newBuilder()
          .programName("scando")
          .addObject(parameters)
          .build();

      commander.parse(args);

      if (parameters.help) {
        commander.usage();
        return 0;
      }

    } catch (final Exception e) {
      System.err.println("ERROR: " + e.getMessage());
      System.err.println("INFO: Try --help for usage information");
      return 1;
    }

    return runCheck(parameters);
  }

  private static int runCheck(
    final Parameters parameters)
    throws Exception
  {
    parameters.oldJarPath = copyFromURI(parameters);
    parameters.newJarPath = parameters.newJarPath.toAbsolutePath();
    parameters.oldJarPath = parameters.oldJarPath.toAbsolutePath();
    parameters.htmlReport = parameters.htmlReport.toAbsolutePath();
    parameters.textReport = parameters.textReport.toAbsolutePath();
    if (parameters.excludeList != null) {
      parameters.excludeList = parameters.excludeList.toAbsolutePath();
    }

    final SemanticVersion newJarVersionValue =
      semanticVersionOf(new Version(parameters.newJarVersion));
    final SemanticVersion oldJarVersionValue =
      semanticVersionOf(new Version(parameters.oldJarVersion));

    if (hashOf(parameters.oldJarPath).equals(hashOf(parameters.newJarPath))) {
      System.err.println(
        "INFO: The input files are identical; any version number change is acceptable");
      return 0;
    }

    final File oldTargetFile;
    if (parameters.oldJarPath.toString().endsWith(".aar")) {
      oldTargetFile = unpackAAR(parameters.oldJarPath);
    } else {
      oldTargetFile = parameters.oldJarPath.toFile();
    }

    final File newTargetFile;
    if (parameters.newJarPath.toString().endsWith(".aar")) {
      newTargetFile = unpackAAR(parameters.newJarPath);
    } else {
      newTargetFile = parameters.newJarPath.toFile();
    }

    final JApiCmpArchive oldArchives =
      new JApiCmpArchive(
        oldTargetFile,
        oldJarVersionValue.toString()
      );
    final JApiCmpArchive newArchives =
      new JApiCmpArchive(
        newTargetFile,
        newJarVersionValue.toString()
      );

    final Options options = Options.newDefault();
    options.getIgnoreMissingClasses().setIgnoreAllMissingClasses(true);
    options.setOutputOnlyModifications(true);
    options.setNoAnnotations(true);
    options.setOldArchives(Collections.singletonList(oldArchives));
    options.setHtmlOutputFile(Optional.of(parameters.htmlReport.toString()));
    options.setNewArchives(Collections.singletonList(newArchives));

    if (parameters.excludeList != null) {
      loadExcludeList(parameters, options);
    }

    final JarArchiveComparatorOptions comparatorOptions =
      JarArchiveComparatorOptions.of(options);
    final JarArchiveComparator jarArchiveComparator =
      new JarArchiveComparator(comparatorOptions);
    final List<JApiClass> jApiClasses =
      jarArchiveComparator.compare(oldArchives, newArchives);

    writeReports(options, jApiClasses, parameters, oldArchives, newArchives);

    return runSemanticVersionCheck(
      jApiClasses,
      newJarVersionValue,
      oldJarVersionValue
    );
  }

  private static Path copyFromURI(
    final Parameters parameters)
    throws IOException, URISyntaxException
  {
    final URI source = parameters.oldJarUri;
    System.err.printf("INFO: Copying %s to temporary file%n", source);

    final URI actualSource;
    if (source.getScheme() == null) {
      actualSource = new URI("file", null, source.getPath(), null);
    } else {
      actualSource = source;
    }

    final String extension =
      FilenameUtils.getExtension(actualSource.getPath());
    final URL sourceUrl =
      actualSource.toURL();

    try {
      try (InputStream inputStream = sourceUrl.openStream()) {
        final Path output = Files.createTempFile("scando", extension);
        try (OutputStream outputStream = Files.newOutputStream(
          output,
          CREATE,
          TRUNCATE_EXISTING,
          WRITE)) {
          IOUtils.copy(inputStream, outputStream);
          return output;
        }
      }
    } catch (final FileNotFoundException e) {
      if (parameters.ignoreMissingOld) {
        System.err.printf(
          "INFO: Ignoring missing source %s (%s), and succeeding trivially.%n",
          source,
          e);
        parameters.oldJarPath = parameters.newJarPath;
        parameters.oldJarVersion = parameters.newJarVersion;
        return parameters.newJarPath;
      }
      throw e;
    }
  }

  private static String hashOf(final Path path)
    throws IOException, NoSuchAlgorithmException
  {
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream stream = Files.newInputStream(path)) {
      final byte[] buffer = new byte[4096];
      while (true) {
        final int r = stream.read(buffer);
        if (r == -1) {
          break;
        }
        digest.update(buffer, 0, r);
      }
    }
    return Hex.encodeHexString(digest.digest());
  }

  /**
   * The main entry point. Exits the process instead of returning.
   *
   * @param args The command-line arguments
   *
   * @throws Exception On errors
   */

  public static void main(
    final String[] args)
    throws Exception
  {
    System.exit(mainExitless(args));
  }

  private static void loadExcludeList(
    final Parameters parameters,
    final Options options)
    throws IOException
  {
    try (Stream<String> lineStream = Files.lines(parameters.excludeList)) {
      final List<String> lines = lineStream.collect(Collectors.toList());
      for (final String line : lines) {
        final String lineTrimmed = line.trim();
        if (lineTrimmed.isEmpty()) {
          continue;
        }
        if (lineTrimmed.startsWith("#")) {
          continue;
        }
        options.addExcludeFromArgument(Optional.of(lineTrimmed), true);
      }
    }
  }

  private static File unpackAAR(
    final Path original)
    throws IOException
  {
    final String filenameWithoutExtension =
      FilenameUtils.removeExtension(original.toString());
    final String newFilename =
      String.format("%s.jar", filenameWithoutExtension);
    final String newFilenameTmp =
      String.format("%s.jar.tmp", filenameWithoutExtension);
    final FileSystem fileSystem =
      original.getFileSystem();
    final Path output =
      fileSystem.getPath(newFilename);
    final Path outputTmp =
      fileSystem.getPath(newFilenameTmp);

    System.err.printf("INFO: Unpacking %s -> %s%n", original, newFilename);

    try (ZipFile file = new ZipFile(original.toFile())) {
      final ZipEntry entry = file.getEntry("classes.jar");
      try (InputStream stream = file.getInputStream(entry)) {
        Files.copy(stream, outputTmp);
        Files.move(outputTmp, output, REPLACE_EXISTING, ATOMIC_MOVE);
      }
    }
    return output.toFile();
  }

  private static int runSemanticVersionCheck(
    final List<JApiClass> jApiClasses,
    final SemanticVersion newJarVersionValue,
    final SemanticVersion oldJarVersionValue)
  {
    final SemverOut semverOut =
      new SemverOut(Options.newDefault(), jApiClasses);
    final SemanticVersion semverVersion =
      semanticVersionOf(new Version(semverOut.generate()));

    final SemanticVersion.ChangeType givenChange =
      oldJarVersionValue.computeChangeType(newJarVersionValue)
        .get();
    final SemanticVersion.ChangeType requiredChange =
      new SemanticVersion(0, 0, 1)
        .computeChangeType(semverVersion)
        .get();

    if (oldJarVersionValue.getMajor() > 0 || newJarVersionValue.getMajor() > 0) {
      if (requiredChange.getRank() > givenChange.getRank()) {
        System.err.println(String.format(
          "ERROR: The version change between %s and %s is %s, "
            + "but the changes made to the code require a %s version change",
          oldJarVersionValue,
          newJarVersionValue,
          givenChange,
          requiredChange
        ));
        System.err.flush();
        return 1;
      }
    }
    return 0;
  }

  private static void writeReports(
    final Options options,
    final List<JApiClass> jApiClasses,
    final Parameters parameters,
    final JApiCmpArchive oldArchives,
    final JApiCmpArchive newArchives)
    throws Exception
  {
    try (BufferedWriter outputStream =
           Files.newBufferedWriter(parameters.textReport)) {
      final StdoutOutputGenerator stdoutOutputGenerator =
        new StdoutOutputGenerator(options, jApiClasses);

      outputStream.write("Old jar:     " + parameters.oldJarPath);
      outputStream.newLine();
      outputStream.write("Old version: " + parameters.oldJarVersion);
      outputStream.newLine();
      outputStream.write("New jar:     " + parameters.newJarPath);
      outputStream.newLine();
      outputStream.write("New version: " + parameters.newJarVersion);
      outputStream.newLine();
      outputStream.newLine();
      outputStream.write(stdoutOutputGenerator.generate());
      outputStream.flush();
    }

    final XmlOutputGeneratorOptions xmlOptions =
      new XmlOutputGeneratorOptions();
    final XmlOutputGenerator xmlOutputGenerator =
      new XmlOutputGenerator(jApiClasses, options, xmlOptions);

    try (XmlOutput xmlOutput = xmlOutputGenerator.generate()) {
      XmlOutputGenerator.writeToFiles(options, xmlOutput);
    }

    System.err.println("INFO: Text report written to " + parameters.textReport);
    System.err.println("INFO: HTML report written to " + parameters.htmlReport);
    System.err.flush();
  }
}
