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

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Main
{
  private Main()
  {

  }

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
      names = "--oldJar",
      description = "The old jar file",
      required = true
    )
    private Path oldJarPath;

    @Parameter(
      names = "--oldJarVersion",
      description = "The old jar version",
      required = true
    )
    private String oldJarVersion;

    @Parameter(
      names = "--newJar",
      description = "The new jar file",
      required = true
    )
    private Path newJarPath;

    @Parameter(
      names = "--newJarVersion",
      description = "The new jar version",
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

  public static void main(
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
        System.exit(0);
      }

    } catch (final Exception e) {
      System.err.println("ERROR: " + e.getMessage());
      System.err.println("INFO: Try --help for usage information");
      System.exit(1);
    }

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

    final JApiCmpArchive oldArchives =
      new JApiCmpArchive(
        parameters.oldJarPath.toFile(),
        oldJarVersionValue.toString()
      );
    final JApiCmpArchive newArchives =
      new JApiCmpArchive(
        parameters.newJarPath.toFile(),
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

    final JarArchiveComparatorOptions comparatorOptions =
      JarArchiveComparatorOptions.of(options);
    final JarArchiveComparator jarArchiveComparator =
      new JarArchiveComparator(comparatorOptions);
    final List<JApiClass> jApiClasses =
      jarArchiveComparator.compare(oldArchives, newArchives);

    writeReports(options, jApiClasses, parameters, oldArchives, newArchives);

    System.exit(
      runSemanticVersionCheck(
        jApiClasses,
        newJarVersionValue,
        oldJarVersionValue
      )
    );
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
