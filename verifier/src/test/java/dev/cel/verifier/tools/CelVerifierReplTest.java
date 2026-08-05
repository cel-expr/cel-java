// Copyright 2026 Google LLC
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

package dev.cel.verifier.tools;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelVerifierReplTest {

  @Before
  public void setUp() {
    System.setProperty("z3.skipLibraryLoad", "true");
  }

  @SuppressWarnings({"PreferCharsetOverload", "JdkObsolete"})
  private String[] runReplWithCommands(String... commands) throws Exception {
    String input = String.join("\n", commands) + "\n";
    BufferedReader reader = new BufferedReader(new StringReader(input));
    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    ByteArrayOutputStream errStream = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(outStream, true, UTF_8.name());
    PrintStream err = new PrintStream(errStream, true, UTF_8.name());

    CelVerifierRepl.runRepl(reader, out, err);

    return new String[] {
      new String(outStream.toByteArray(), UTF_8), new String(errStream.toByteArray(), UTF_8)
    };
  }

  @Test
  public void repl_quitAndExit() throws Exception {
    String[] output1 = runReplWithCommands(":quit");
    assertThat(output1[0]).contains("Goodbye!");

    String[] output2 = runReplWithCommands(":exit");
    assertThat(output2[0]).contains("Goodbye!");
  }

  @Test
  public void repl_helpCommands() throws Exception {
    String[] output =
        runReplWithCommands(
            ":help",
            ":help var",
            ":help unknown",
            ":help timeout",
            ":help unroll",
            ":help sat",
            ":help valid",
            ":help equiv",
            ":help non_existent_topic",
            ":quit");
    assertThat(output[0]).contains("REPL Commands:");
    assertThat(output[0]).contains("Command: :var <name> <type>");
    assertThat(output[0]).contains("Command: :unknown <identifier>");
    assertThat(output[0]).contains("Command: :timeout <seconds>");
    assertThat(output[0]).contains("Command: :unroll <limit>");
    assertThat(output[0]).contains("Query: sat <expression>");
    assertThat(output[0]).contains("Query: valid <expression>");
    assertThat(output[0]).contains("Query: equiv <expression1> <=> <expression2>");
  }

  @Test
  public void repl_varDeclarations() throws Exception {
    String[] output =
        runReplWithCommands(
            ":var role string",
            ":var port int",
            ":var scores map<string,int>",
            ":var tags list<string>",
            ":vars",
            ":quit");
    assertThat(output[0]).contains("Variable declared: role : string");
    assertThat(output[0]).contains("Variable declared: port : int");
    assertThat(output[0]).contains("Variable declared: scores : map(string, int)");
    assertThat(output[0]).contains("Variable declared: tags : list(string)");
    assertThat(output[0]).contains("Variables (4):");
  }

  @Test
  public void repl_unknownIdentifiers() throws Exception {
    String[] output =
        runReplWithCommands(":unknown request.headers", ":unknown request.auth", ":vars", ":quit");
    assertThat(output[0]).contains("Added unknown identifier: 'request.headers'");
    assertThat(output[0]).contains("Added unknown identifier: 'request.auth'");
    assertThat(output[0]).contains("Unknowns: [request.headers, request.auth]");
  }

  @Test
  public void repl_timeoutConfiguration() throws Exception {
    String[] output =
        runReplWithCommands(
            ":timeout 15", ":vars", ":timeout -5", ":timeout abc", ":timeout", ":quit");
    assertThat(output[0]).contains("Timeout set to 15s.");
    assertThat(output[0]).contains("Timeout: 15s");
    assertThat(output[1]).contains("Timeout must be a positive integer.");
    assertThat(output[1]).contains("Invalid timeout value.");
    assertThat(output[1]).contains("Usage: :timeout <seconds>");
  }

  @Test
  public void repl_unrollConfiguration() throws Exception {
    String[] output =
        runReplWithCommands(":unroll 10", ":vars", ":unroll -1", ":unroll xyz", ":unroll", ":quit");
    assertThat(output[0]).contains("Comprehension unroll limit set to 10.");
    assertThat(output[0]).contains("Unroll limit: 10");
    assertThat(output[1]).contains("Unroll limit must be non-negative.");
    assertThat(output[1]).contains("Invalid unroll limit value.");
    assertThat(output[1]).contains("Usage: :unroll <limit>");
  }

  @Test
  public void repl_sessionStateAndClear() throws Exception {
    String[] output =
        runReplWithCommands(
            ":var role string", ":unknown req.headers", ":vars", ":clear", ":vars", ":quit");
    assertThat(output[0]).contains("Variables (1):");
    assertThat(output[0]).contains("Session state reset.");
    assertThat(output[0]).contains("Variables (0):");
    assertThat(output[0]).contains("Unknowns: none");
  }

  @Test
  public void repl_satQueries() throws Exception {
    String[] output =
        runReplWithCommands(":var port int", "sat port > 1024", "port > 1024", "sat", ":quit");
    assertThat(output[0]).contains("[VERIFIED]");
    assertThat(output[1]).contains("Usage: sat <expression>");
  }

  @Test
  public void repl_validQueries() throws Exception {
    String[] output =
        runReplWithCommands(":var x int", "valid x > 0 || x <= 0", "valid x > 0", "valid", ":quit");
    assertThat(output[0]).contains("[VERIFIED]");
    assertThat(output[0]).contains("[VIOLATED]");
    assertThat(output[1]).contains("Usage: valid <expression>");
  }

  @Test
  public void repl_equivQueries() throws Exception {
    String[] output =
        runReplWithCommands(":var x int", "equiv x > 10 <=> 10 < x", "equiv x > 10", ":quit");
    assertThat(output[0]).contains("[VERIFIED]");
    assertThat(output[1]).contains("Equivalence query format: equiv <expr1> <=> <expr2>");
  }

  @Test
  public void repl_equivDoubleNegation() throws Exception {
    String[] output = runReplWithCommands(":var x int", "equiv !!(x == 10) <=> (x == 10)", ":quit");
    assertThat(output[0]).contains("[VERIFIED]");
  }

  @Test
  public void repl_unknownCommandsAndErrors() throws Exception {
    String[] output =
        runReplWithCommands(
            ":unknowncommand",
            ":var",
            ":var invalid_spec",
            ":var x foo_type",
            ":unknown",
            "invalid + + syntax",
            ":quit");
    assertThat(output[1]).contains("Unknown command: :unknowncommand");
    assertThat(output[1]).contains("Usage: :var <name> <type>");
    assertThat(output[1]).contains("Unsupported type");
    assertThat(output[1]).contains("Usage: :unknown <identifier>");
    assertThat(output[1]).contains("Compilation error");
  }
}
