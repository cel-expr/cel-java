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

import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.CelVerificationResult;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Interactive REPL shell for CEL formal verification. */
public final class CelVerifierRepl {

  private CelVerifierRepl() {}

  public static int runInteractiveRepl() {
    System.out.println("============================================================");
    System.out.println(" CEL Verification REPL (cel-java v0.14.0)");
    System.out.println(" Type :help for commands, :quit to exit.");
    System.out.println("============================================================");

    Map<String, CelType> sessionVars = new HashMap<>();
    List<String> unknownIdentifiers = new ArrayList<>();
    int timeoutSeconds = 10;
    int unrollLimit = 5;

    org.jline.reader.LineReader lineReader = null;
    BufferedReader fallbackReader = null;
    try {
      org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder.builder().system(true).build();
      lineReader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
    } catch (Exception e) {
      fallbackReader =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    String prompt = FormatUtils.ANSI_CYAN + "cel-verifier> " + FormatUtils.ANSI_RESET;

    while (true) {
      String line;
      try {
        if (lineReader != null) {
          line = lineReader.readLine(prompt);
        } else {
          System.out.print(prompt);
          System.out.flush();
          line = fallbackReader.readLine();
          if (line == null) {
            break; // EOF
          }
        }
      } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
        System.out.println("Goodbye!");
        break;
      } catch (Exception e) {
        System.err.println("Error reading input: " + e.getMessage());
        break;
      }

      line = line.trim();
      if (line.isEmpty()) {
        continue;
      }

      if (line.equalsIgnoreCase(":quit") || line.equalsIgnoreCase(":exit")) {
        System.out.println("Goodbye!");
        break;
      }

      if (line.startsWith(":help")) {
        String sub = line.substring(5).trim();
        printHelp(sub);
        continue;
      }

      if (line.equalsIgnoreCase(":vars")) {
        printVars(sessionVars, unknownIdentifiers, timeoutSeconds, unrollLimit);
        continue;
      }

      if (line.equalsIgnoreCase(":clear")) {
        sessionVars.clear();
        unknownIdentifiers.clear();
        System.out.println("Session state reset.");
        continue;
      }

      if (line.startsWith(":var ")) {
        handleVarCommand(line.substring(5).trim(), sessionVars);
        continue;
      }

      if (line.startsWith(":unknown ")) {
        String ident = line.substring(9).trim();
        if (!ident.isEmpty()) {
          unknownIdentifiers.add(ident);
          System.out.println("Added unknown identifier: '" + ident + "'");
        }
        continue;
      }

      if (line.startsWith(":timeout ")) {
        try {
          timeoutSeconds = Integer.parseInt(line.substring(9).trim());
          System.out.println("Timeout set to " + timeoutSeconds + "s.");
        } catch (NumberFormatException e) {
          System.err.println("Invalid timeout value.");
        }
        continue;
      }

      if (line.startsWith(":unroll ")) {
        try {
          unrollLimit = Integer.parseInt(line.substring(8).trim());
          System.out.println("Comprehension unroll limit set to " + unrollLimit + ".");
        } catch (NumberFormatException e) {
          System.err.println("Invalid unroll limit value.");
        }
        continue;
      }

      // Handle queries
      VerificationOptions options =
          VerificationOptions.builder()
              .setTimeout(Duration.ofSeconds(timeoutSeconds))
              .setComprehensionUnrollLimit(unrollLimit)
              .setUnknownIdentifiers(unknownIdentifiers)
              .build();

      try {
        if (line.startsWith("sat ")) {
          String expr = line.substring(4).trim();
          CelVerificationResult res =
              CelVerifierToolCore.checkSatisfiable(expr, sessionVars, options);
          System.out.println(FormatUtils.formatTextResult(res));
        } else if (line.startsWith("valid ")) {
          String expr = line.substring(6).trim();
          CelVerificationResult res =
              CelVerifierToolCore.checkValid(expr, sessionVars, options);
          System.out.println(FormatUtils.formatTextResult(res));
        } else if (line.startsWith("equiv ")) {
          String rest = line.substring(6).trim();
          String[] parts = splitEquivQuery(rest);
          if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            System.err.println(
                "Equivalence query format: equiv <expr1> <=> <expr2>");
          } else {
            String exprA = parts[0].trim();
            String exprB = parts[1].trim();
            CelVerificationResult res =
                CelVerifierToolCore.verifyEquivalence(exprA, exprB, sessionVars, options);
            System.out.println(FormatUtils.formatTextResult(res));
          }
        } else {
          // Default: treat as sat query
          CelVerificationResult res =
              CelVerifierToolCore.checkSatisfiable(line, sessionVars, options);
          System.out.println(FormatUtils.formatTextResult(res));
        }
      } catch (dev.cel.common.CelValidationException e) {
        System.err.println(FormatUtils.ANSI_RED + "Compilation error:\n" + e.getMessage() + FormatUtils.ANSI_RESET);
      } catch (dev.cel.policy.CelPolicyValidationException e) {
        System.err.println(FormatUtils.ANSI_RED + "Policy compilation error:\n" + e.getMessage() + FormatUtils.ANSI_RESET);
      } catch (Exception e) {
        System.err.println(FormatUtils.ANSI_RED + "Verification failed: " + e.getMessage() + FormatUtils.ANSI_RESET);
      }
    }
    return 0;
  }

  private static void handleVarCommand(String arg, Map<String, CelType> sessionVars) {
    String[] parts = arg.split("\\s+", 2);
    if (parts.length != 2) {
      System.err.println("Usage: :var <name> <type> (e.g. :var role string, :var scores map<string,int>)");
      return;
    }
    String name = parts[0].trim();
    String typeStr = parts[1].trim();
    try {
      CelType type = VerificationOptions.parseCelType(typeStr);
      sessionVars.put(name, type);
      System.out.println("Variable declared: " + name + " : " + CelTypes.format(type));
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
    }
  }

  private static void printVars(
      Map<String, CelType> sessionVars,
      List<String> unknowns,
      int timeoutSeconds,
      int unrollLimit) {
    System.out.println("--- Session State ---");
    System.out.println("Timeout: " + timeoutSeconds + "s | Unroll limit: " + unrollLimit);
    System.out.println("Unknowns: " + (unknowns.isEmpty() ? "none" : unknowns));
    System.out.println("Variables (" + sessionVars.size() + "):");
    for (Map.Entry<String, CelType> entry : sessionVars.entrySet()) {
      System.out.println("  " + entry.getKey() + " : " + CelTypes.format(entry.getValue()));
    }
  }

  private static void printHelp() {
    printHelp("");
  }

  private static void printHelp(String topic) {
    String t = topic.toLowerCase(Locale.US).replace(":", "").trim();
    switch (t) {
      case "var":
      case "vars":
        System.out.println("Command: :var <name> <type>");
        System.out.println("Declares a variable in the REPL session with a specific type.");
        System.out.println();
        System.out.println("Supported Types:");
        System.out.println("  - Primitive types: int, uint, string, bool, double, bytes");
        System.out.println("  - List types:      list<T> (e.g., list<int>, list<string>)");
        System.out.println("  - Map types:       map<K,V> (e.g., map<string,int>, map<string,string>)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cel-verifier> :var role string");
        System.out.println("  cel-verifier> :var port int");
        System.out.println("  cel-verifier> :var scores map<string,int>");
        System.out.println("  cel-verifier> :var tags list<string>");
        break;
      case "unknown":
        System.out.println("Command: :unknown <identifier>");
        System.out.println("Marks an identifier path as 'Unknown' during verification (partial evaluation).");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cel-verifier> :unknown request.headers");
        System.out.println("  cel-verifier> :unknown request.auth.claims");
        break;
      case "timeout":
        System.out.println("Command: :timeout <seconds>");
        System.out.println("Configures the Z3 solver soft timeout duration in seconds (default: 10s).");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cel-verifier> :timeout 5");
        break;
      case "unroll":
        System.out.println("Command: :unroll <limit>");
        System.out.println("Configures the BMC loop unroll limit for comprehensions (default: 5).");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cel-verifier> :unroll 3");
        break;
      case "sat":
        System.out.println("Query: sat <expression>");
        System.out.println("Checks if a CEL expression can evaluate to true for any possible input assignments.");
        System.out.println("If satisfiable, outputs concrete satisfying witness values.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cel-verifier> sat role == 'editor' && port > 1024");
        System.out.println("  cel-verifier> sat scores['alice'] > 90");
        break;
      case "valid":
        System.out.println("Query: valid <expression>");
        System.out.println("Proves whether a CEL expression evaluates to true for ALL possible input assignments.");
        System.out.println("If invalid, outputs a counterexample showing inputs causing it to fail.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cel-verifier> valid x > 10 || x <= 10");
        break;
      case "equiv":
        System.out.println("Query: equiv <expression1> <=> <expression2>");
        System.out.println("Proves whether two CEL expressions are semantically identical for all inputs.");
        System.out.println("If not equivalent, outputs a counterexample showing inputs where they diverge.");
        System.out.println();
        System.out.println("Use '<=>' as the recommended separator between expressions.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  cel-verifier> equiv x > 10 <=> 10 < x");
        System.out.println("  cel-verifier> equiv (a && b) || (a && c) <=> a && (b || c)");
        System.out.println("  cel-verifier> equiv string_int_map == {'a': 1, 'b': 2} ? string_int_map.all(k, k == 'a') : true <=> string_int_map == {'a': 1, 'b': 2} ? string_int_map.all(k, k == 'a') : true");
        break;
      default:
        System.out.println("REPL Commands:");
        System.out.println("  :var <name> <type>          Declare variable (e.g. :var role string, :var m map<string,int>)");
        System.out.println("  :unknown <identifier>       Mark identifier as unknown");
        System.out.println("  :timeout <seconds>          Set solver timeout (default: 10s)");
        System.out.println("  :unroll <limit>             Set comprehension unroll limit (default: 5)");
        System.out.println("  :vars                       List session variables & options");
        System.out.println("  :clear                      Reset session state");
        System.out.println("  :help [command]             Display help message or specific command details");
        System.out.println("  :quit                       Exit REPL");
        System.out.println();
        System.out.println("Verification Queries:");
        System.out.println("  sat <expr>                  Check satisfiability");
        System.out.println("  valid <expr>                Check validity (always true)");
        System.out.println("  equiv <expr1> <=> <expr2>   Prove logical equivalence");
        System.out.println("  <expr>                      Check satisfiability (default)");
        System.out.println();
        System.out.println("Type ':help <command>' (e.g. ':help var', ':help sat') for detailed usage and examples.");
        break;
    }
  }

  public static String[] splitEquivQuery(String rest) {
    if (rest == null || rest.trim().isEmpty()) {
      return new String[0];
    }
    String input = rest.trim();
    if (input.contains(" <=> ")) {
      return input.split(" <=> ", 2);
    }
    if (input.contains(" === ")) {
      return input.split(" === ", 2);
    }
    if (input.contains(" , ")) {
      return input.split(" , ", 2);
    }
    return new String[0];
  }
}

