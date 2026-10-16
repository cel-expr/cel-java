// Copyright 2024 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.conformance;

import static dev.cel.conformance.ConformanceTest.CONFORMANCE_EXTENSION_REGISTRY;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.protobuf.TextFormat;
import dev.cel.conformance.ConformanceTest.RuntimeType;
import dev.cel.expr.conformance.test.SimpleTest;
import dev.cel.expr.conformance.test.SimpleTestFile;
import dev.cel.expr.conformance.test.SimpleTestSection;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestClass;

public final class ConformanceTestRunner extends ParentRunner<ConformanceTest> {

  private static final Splitter SPLITTER = Splitter.on(",").omitEmptyStrings();

  private final ImmutableSortedMap<String, SimpleTestFile> testFiles;
  private final ImmutableList<String> testsToSkip;
  private final RuntimeType runtimeType;

  private static ImmutableSortedMap<String, SimpleTestFile> loadTestFiles() {
    List<String> testPaths =
        SPLITTER.splitToList(System.getProperty("dev.cel.conformance.ConformanceTests.tests"));
    try {
      TextFormat.Parser parser =
          TextFormat.Parser.newBuilder()
              .setTypeRegistry(ConformanceTest.CONFORMANCE_TYPE_REGISTRY)
              .build();
      ImmutableSortedMap.Builder<String, SimpleTestFile> testFiles =
          ImmutableSortedMap.naturalOrder();
      for (String testPath : testPaths) {
        SimpleTestFile.Builder fileBuilder = SimpleTestFile.newBuilder();
        try (BufferedReader input = Files.newBufferedReader(Paths.get(testPath), UTF_8)) {
          parser.merge(input, CONFORMANCE_EXTENSION_REGISTRY, fileBuilder);
        }
        SimpleTestFile testFile = fileBuilder.build();
        testFiles.put(testFile.getName(), testFile);
      }
      return testFiles.buildOrThrow();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public ConformanceTestRunner(Class<?> clazz) throws InitializationError {
    super(new TestClass(clazz));
    Preconditions.checkArgument(ConformanceTests.class.equals(clazz));
    testFiles = loadTestFiles();
    testsToSkip =
        ImmutableList.copyOf(
            SPLITTER.splitToList(
                System.getProperty("dev.cel.conformance.ConformanceTests.skip_tests")));
    runtimeType =
        RuntimeType.valueOf(
            System.getProperty("dev.cel.conformance.ConformanceTests.runtime", "LEGACY"));
  }

  private boolean shouldSkipTest(String name) {
    for (String testToSkip : testsToSkip) {
      if (name.startsWith(testToSkip)) {
        String consumedName = name.substring(testToSkip.length());
        if (consumedName.isEmpty() || consumedName.startsWith("/")) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  protected List<ConformanceTest> getChildren() {
    ArrayList<ConformanceTest> tests = new ArrayList<>();
    for (SimpleTestFile testFile : testFiles.values()) {
      for (SimpleTestSection testSection : testFile.getSectionList()) {
        for (SimpleTest test : testSection.getTestList()) {
          String name =
              String.format("%s/%s/%s", testFile.getName(), testSection.getName(), test.getName());
          tests.add(new ConformanceTest(name, test, shouldSkipTest(name), runtimeType));
        }
      }
    }
    return tests;
  }

  @Override
  protected Description describeChild(ConformanceTest child) {
    return Description.createTestDescription(
        ConformanceTests.class, child.getName(), ConformanceTest.class.getAnnotations());
  }

  @Override
  protected void runChild(ConformanceTest child, RunNotifier notifier) {
    Description desc = describeChild(child);
    if (isIgnored(child)) {
      notifier.fireTestIgnored(desc);
    } else {
      runLeaf(child, desc, notifier);
    }
  }

  @Override
  protected boolean isIgnored(ConformanceTest child) {
    return child.shouldSkip();
  }
}
