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

package com.io7m.scando.tests;

import com.io7m.scando.cmdline.Main;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class MainTest
{
  private Path directory;

  @BeforeEach
  public void setup()
    throws IOException
  {
    this.directory = TestDirectories.createTempDirectory();
  }

  @Test
  public void nonsenseArguments0()
    throws Exception
  {
    final int result = Main.mainExitless(new String[]{
      "--what?"
    });

    assertEquals(1, result);
  }

  @Test
  public void noArguments0()
    throws Exception
  {
    final int result = Main.mainExitless(new String[]{

    });

    assertEquals(1, result);
  }

  @Test
  public void help()
    throws Exception
  {
    final int result = Main.mainExitless(new String[]{
      "--help"
    });

    assertEquals(0, result);
  }

  @Test
  public void nonexistentJarsFail0()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");
    final Path newJar =
      this.directory.resolve("nonexistent");

    assertThrows(IOException.class, () -> {
      Main.mainExitless(new String[]{
        "--oldJar",
        oldJar.toString(),
        "--oldJarVersion",
        "7.1.0",
        "--newJar",
        newJar.toString(),
        "--newJarVersion",
        "7.1.0",
        "--textReport",
        this.directory.resolve("report.txt").toString(),
        "--htmlReport",
        this.directory.resolve("report.html").toString(),
      });
    });
  }

  @Test
  public void nonexistentJarsFail1()
    throws Exception
  {
    final Path oldJar =
      this.directory.resolve("nonexistent");
    final Path newJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");

    assertThrows(IOException.class, () -> {
      Main.mainExitless(new String[]{
        "--oldJar",
        oldJar.toString(),
        "--oldJarVersion",
        "7.1.0",
        "--newJar",
        newJar.toString(),
        "--newJarVersion",
        "7.1.0",
        "--textReport",
        this.directory.resolve("report.txt").toString(),
        "--htmlReport",
        this.directory.resolve("report.html").toString(),
      });
    });
  }

  @Test
  public void unparseableVersion()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");
    final Path newJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");

    assertThrows(IllegalArgumentException.class, () -> {
      Main.mainExitless(new String[]{
        "--oldJar",
        oldJar.toString(),
        "--oldJarVersion",
        "x",
        "--newJar",
        newJar.toString(),
        "--newJarVersion",
        "y",
        "--textReport",
        this.directory.resolve("report.txt").toString(),
        "--htmlReport",
        this.directory.resolve("report.html").toString(),
      });
    });
  }

  @Test
  public void identicalJarsAreCompatible()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");
    final Path newJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");

    final int result = Main.mainExitless(new String[]{
      "--oldJar",
      oldJar.toString(),
      "--oldJarVersion",
      "7.1.0",
      "--newJar",
      newJar.toString(),
      "--newJarVersion",
      "7.1.0",
      "--textReport",
      this.directory.resolve("report.txt").toString(),
      "--htmlReport",
      this.directory.resolve("report.html").toString(),
    });

    assertEquals(0, result);
  }

  @Test
  public void majorVersionChangeRequiredButExcluded()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");
    final Path newJar =
      this.resourceOf("com.io7m.jtensors.core-8.0.0.jar");

    final Path excludeFile = this.directory.resolve("excludes.txt");
    try (BufferedWriter writer = Files.newBufferedWriter(excludeFile, CREATE)) {
      writer.append("# Comment!");
      writer.newLine();
      writer.append(" ");
      writer.newLine();
      writer.append("com.io7m.jtensors.*");
      writer.newLine();
      writer.flush();
    }

    final int result = Main.mainExitless(new String[]{
      "--oldJar",
      oldJar.toString(),
      "--oldJarVersion",
      "7.1.0",
      "--newJar",
      newJar.toString(),
      "--newJarVersion",
      "7.1.1",
      "--excludeList",
      excludeFile.toString(),
      "--textReport",
      this.directory.resolve("report.txt").toString(),
      "--htmlReport",
      this.directory.resolve("report.html").toString(),
    });

    assertEquals(0, result);
  }

  @Test
  public void majorVersionChangeRequired()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");
    final Path newJar =
      this.resourceOf("com.io7m.jtensors.core-8.0.0.jar");

    final int result = Main.mainExitless(new String[]{
      "--oldJar",
      oldJar.toString(),
      "--oldJarVersion",
      "7.1.0",
      "--newJar",
      newJar.toString(),
      "--newJarVersion",
      "7.1.0",
      "--textReport",
      this.directory.resolve("report.txt").toString(),
      "--htmlReport",
      this.directory.resolve("report.html").toString(),
    });

    assertEquals(1, result);
  }

  @Test
  public void majorVersionChangeRequiredOK()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.jar");
    final Path newJar =
      this.resourceOf("com.io7m.jtensors.core-8.0.0.jar");

    final int result = Main.mainExitless(new String[]{
      "--oldJar",
      oldJar.toString(),
      "--oldJarVersion",
      "7.1.0",
      "--newJar",
      newJar.toString(),
      "--newJarVersion",
      "8.0.0",
      "--textReport",
      this.directory.resolve("report.txt").toString(),
      "--htmlReport",
      this.directory.resolve("report.html").toString(),
    });

    assertEquals(0, result);
  }

  @Test
  public void majorVersionChangeRequiredAAR()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.aar");
    final Path newJar =
      this.resourceOf("com.io7m.jtensors.core-8.0.0.aar");

    final int result = Main.mainExitless(new String[]{
      "--oldJar",
      oldJar.toString(),
      "--oldJarVersion",
      "7.1.0",
      "--newJar",
      newJar.toString(),
      "--newJarVersion",
      "7.1.0",
      "--textReport",
      this.directory.resolve("report.txt").toString(),
      "--htmlReport",
      this.directory.resolve("report.html").toString(),
    });

    assertEquals(1, result);
  }

  @Test
  public void majorVersionChangeRequiredAAROK()
    throws Exception
  {
    final Path oldJar =
      this.resourceOf("io7m-jtensors-core-7.1.0.aar");
    final Path newJar =
      this.resourceOf("com.io7m.jtensors.core-8.0.0.aar");

    final int result = Main.mainExitless(new String[]{
      "--oldJar",
      oldJar.toString(),
      "--oldJarVersion",
      "7.1.0",
      "--newJar",
      newJar.toString(),
      "--newJarVersion",
      "8.0.0",
      "--textReport",
      this.directory.resolve("report.txt").toString(),
      "--htmlReport",
      this.directory.resolve("report.html").toString(),
    });

    assertEquals(0, result);
  }

  private Path resourceOf(
    final String name)
    throws IOException
  {
    return TestDirectories.resourceOf(MainTest.class, this.directory, name);
  }
}
