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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypes;
import dev.cel.policy.CelPolicyValidationException;
import dev.cel.verifier.CelVerificationResult;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** Interactive REPL shell for CEL formal verification. */
final class CelVerifierRepl {

  private CelVerifierRepl() {}

  static int runInteractiveRepl() {
    LineReader lineReader = null;
    BufferedReader fallbackReader = null;
    try {
      Terminal terminal = TerminalBuilder.builder().system(true).build();
      lineReader =
          LineReaderBuilder.builder()
              .terminal(terminal)
              .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
              .build();
    } catch (Exception e) {
      fallbackReader = new BufferedReader(new InputStreamReader(System.in, UTF_8));
    }
    return runReplInternal(lineReader, fallbackReader, System.out, System.err);
  }

  static int runRepl(BufferedReader reader, PrintStream out, PrintStream err) {
    return runReplInternal(null, reader, out, err);
  }

  private static int runReplInternal(
      LineReader lineReader, BufferedReader fallbackReader, PrintStream out, PrintStream err) {
    out.println("============================================================");
    out.println(" CEL Verification REPL");
    out.println(" Type :help for commands, :quit to exit.");
    out.println("============================================================");

    Map<String, CelType> sessionVars = new HashMap<>();
    List<String> unknownIdentifiers = new ArrayList<>();
    int timeoutSeconds = 10;
    int unrollLimit = 5;

    String prompt = FormatUtils.ANSI_CYAN + "cel-verifier> " + FormatUtils.ANSI_RESET;

    while (true) {
      String line;
      try {
        if (lineReader != null) {
          line = lineReader.readLine(prompt);
        } else if (fallbackReader != null) {
          out.print(prompt);
          out.flush();
          line = fallbackReader.readLine();
          if (line == null) {
            break; // EOF
          }
        } else {
          break;
        }
      } catch (UserInterruptException | EndOfFileException e) {
        out.println("Goodbye!");
        break;
      } catch (Exception e) {
        err.println("Error reading input: " + e.getMessage());
        break;
      }

      line = line.trim();
      if (line.isEmpty()) {
        continue;
      }

      if (line.startsWith(":")) {
        if (Ascii.equalsIgnoreCase(line, ":quit") || Ascii.equalsIgnoreCase(line, ":exit")) {
          out.println("Goodbye!");
          break;
        }

        Optional<String> helpArg = extractCommandArg(line, ":help");
        if (helpArg.isPresent()) {
          printHelp(helpArg.get(), out);
          continue;
        }

        if (Ascii.equalsIgnoreCase(line, ":vars")) {
          printVars(sessionVars, unknownIdentifiers, timeoutSeconds, unrollLimit, out);
          continue;
        }

        if (Ascii.equalsIgnoreCase(line, ":clear")) {
          sessionVars.clear();
          unknownIdentifiers.clear();
          out.println("Session state reset.");
          continue;
        }

        Optional<String> varArg = extractCommandArg(line, ":var");
        if (varArg.isPresent()) {
          String arg = varArg.get();
          if (arg.isEmpty()) {
            err.println(
                "Usage: :var <name> <type> (e.g. :var role string, :var scores map<string,int>)");
          } else {
            handleVarCommand(arg, sessionVars, out, err);
          }
          continue;
        }

        Optional<String> unknownArg = extractCommandArg(line, ":unknown");
        if (unknownArg.isPresent()) {
          String arg = unknownArg.get();
          if (arg.isEmpty()) {
            err.println("Usage: :unknown <identifier>");
          } else {
            unknownIdentifiers.add(arg);
            out.println("Added unknown identifier: '" + arg + "'");
          }
          continue;
        }

        Optional<String> timeoutArg = extractCommandArg(line, ":timeout");
        if (timeoutArg.isPresent()) {
          String arg = timeoutArg.get();
          if (arg.isEmpty()) {
            err.println("Usage: :timeout <seconds>");
          } else {
            try {
              int t = Integer.parseInt(arg);
              if (t <= 0) {
                err.println("Timeout must be a positive integer.");
              } else {
                timeoutSeconds = t;
                out.println("Timeout set to " + timeoutSeconds + "s.");
              }
            } catch (NumberFormatException e) {
              err.println("Invalid timeout value.");
            }
          }
          continue;
        }

        Optional<String> unrollArg = extractCommandArg(line, ":unroll");
        if (unrollArg.isPresent()) {
          String arg = unrollArg.get();
          if (arg.isEmpty()) {
            err.println("Usage: :unroll <limit>");
          } else {
            try {
              int u = Integer.parseInt(arg);
              if (u < 0) {
                err.println("Unroll limit must be non-negative.");
              } else {
                unrollLimit = u;
                out.println("Comprehension unroll limit set to " + unrollLimit + ".");
              }
            } catch (NumberFormatException e) {
              err.println("Invalid unroll limit value.");
            }
          }
          continue;
        }

        err.println("Unknown command: " + line + ". Type :help for commands.");
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
        Optional<String> satArg = extractCommandArg(line, "sat");
        Optional<String> validArg = extractCommandArg(line, "valid");
        Optional<String> equivArg = extractCommandArg(line, "equiv");

        if (satArg.isPresent()) {
          String arg = satArg.get();
          if (arg.isEmpty()) {
            err.println("Usage: sat <expression>");
          } else {
            CelVerificationResult res =
                CelVerifierToolCore.checkSatisfiable(arg, sessionVars, options);
            out.println(FormatUtils.formatTextResult(res));
          }
        } else if (validArg.isPresent()) {
          String arg = validArg.get();
          if (arg.isEmpty()) {
            err.println("Usage: valid <expression>");
          } else {
            CelVerificationResult res = CelVerifierToolCore.checkValid(arg, sessionVars, options);
            out.println(FormatUtils.formatTextResult(res));
          }
        } else if (equivArg.isPresent()) {
          String arg = equivArg.get();
          ImmutableList<String> parts = splitEquivQuery(arg);
          if (parts.size() != 2 || parts.get(0).isEmpty() || parts.get(1).isEmpty()) {
            err.println("Equivalence query format: equiv <expr1> <=> <expr2>");
          } else {
            String exprA = parts.get(0).trim();
            String exprB = parts.get(1).trim();
            CelVerificationResult res =
                CelVerifierToolCore.verifyEquivalence(exprA, exprB, sessionVars, options);
            out.println(FormatUtils.formatTextResult(res));
          }
        } else {
          // Default: treat as sat query
          CelVerificationResult res =
              CelVerifierToolCore.checkSatisfiable(line, sessionVars, options);
          out.println(FormatUtils.formatTextResult(res));
        }
      } catch (CelValidationException e) {
        err.println(
            FormatUtils.ANSI_RED
                + "Compilation error:\n"
                + e.getMessage()
                + FormatUtils.ANSI_RESET);
      } catch (CelPolicyValidationException e) {
        err.println(
            FormatUtils.ANSI_RED
                + "Policy compilation error:\n"
                + e.getMessage()
                + FormatUtils.ANSI_RESET);
      } catch (Exception e) {
        err.println(
            FormatUtils.ANSI_RED
                + "Verification failed: "
                + e.getMessage()
                + FormatUtils.ANSI_RESET);
      }
    }
    return 0;
  }

  private static void handleVarCommand(
      String arg, Map<String, CelType> sessionVars, PrintStream out, PrintStream err) {
    String[] parts = arg.split("\\s+", 2);
    if (parts.length != 2) {
      err.println("Usage: :var <name> <type> (e.g. :var role string, :var scores map<string,int>)");
      return;
    }
    String name = parts[0].trim();
    String typeStr = parts[1].trim();
    try {
      CelType type = VerificationOptions.parseCelType(typeStr);
      sessionVars.put(name, type);
      out.println("Variable declared: " + name + " : " + CelTypes.format(type));
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
    }
  }

  private static void printVars(
      Map<String, CelType> sessionVars,
      List<String> unknowns,
      int timeoutSeconds,
      int unrollLimit,
      PrintStream out) {
    out.println("--- Session State ---");
    out.println("Timeout: " + timeoutSeconds + "s | Unroll limit: " + unrollLimit);
    out.println("Unknowns: " + (unknowns.isEmpty() ? "none" : unknowns));
    out.println("Variables (" + sessionVars.size() + "):");
    for (Map.Entry<String, CelType> entry : sessionVars.entrySet()) {
      out.println("  " + entry.getKey() + " : " + CelTypes.format(entry.getValue()));
    }
  }

  private static void printHelp(String topic, PrintStream out) {
    String t = topic.toLowerCase(Locale.US).replace(":", "").trim();
    switch (t) {
      case "var":
      case "vars":
        out.println("Command: :var <name> <type>");
        out.println("Declares a variable in the REPL session with a specific type.");
        out.println();
        out.println("Supported Types:");
        out.println("  - Primitive types:  int, uint, string, bool, double, bytes, dyn");
        out.println("  - Well-known types: timestamp, duration");
        out.println("  - List types:       list<T> (e.g., list<int>, list<string>)");
        out.println("  - Map types:        map<K,V> (e.g., map<string,int>, map<string,string>)");
        out.println("  - Optional types:   optional<T> (e.g., optional<string>, optional<int>)");
        out.println("  - Protobuf types:   coming soon");
        out.println();
        out.println("Examples:");
        out.println("  cel-verifier> :var role string");
        out.println("  cel-verifier> :var port int");
        out.println("  cel-verifier> :var scores map<string,int>");
        out.println("  cel-verifier> :var tags list<string>");
        out.println("  cel-verifier> :var created_at timestamp");
        out.println("  cel-verifier> :var timeout duration");
        out.println("  cel-verifier> :var opt_flag optional<bool>");
        break;
      case "unknown":
        out.println("Command: :unknown <identifier>");
        out.println(
            "Marks an identifier path as 'Unknown' during verification (partial evaluation).");
        out.println();
        out.println("Examples:");
        out.println("  cel-verifier> :unknown request.headers");
        out.println("  cel-verifier> :unknown request.auth.claims");
        break;
      case "timeout":
        out.println("Command: :timeout <seconds>");
        out.println("Configures the Z3 solver soft timeout duration in seconds (default: 10s).");
        out.println();
        out.println("Examples:");
        out.println("  cel-verifier> :timeout 5");
        break;
      case "unroll":
        out.println("Command: :unroll <limit>");
        out.println("Configures the BMC loop unroll limit for comprehensions (default: 5).");
        out.println();
        out.println("Examples:");
        out.println("  cel-verifier> :unroll 3");
        break;
      case "sat":
        out.println("Query: sat <expression>");
        out.println(
            "Checks if a CEL expression can evaluate to true for any possible input assignments.");
        out.println("If satisfiable, outputs concrete satisfying witness values.");
        out.println();
        out.println("Examples:");
        out.println("  cel-verifier> sat role == 'editor' && port > 1024");
        out.println("  cel-verifier> sat scores['alice'] > 90");
        break;
      case "valid":
        out.println("Query: valid <expression>");
        out.println(
            "Proves whether a CEL expression evaluates to true for ALL possible input"
                + " assignments.");
        out.println("If invalid, outputs a counterexample showing inputs causing it to fail.");
        out.println();
        out.println("Examples:");
        out.println("  cel-verifier> valid x > 10 || x <= 10");
        break;
      case "equiv":
        out.println("Query: equiv <expression1> <=> <expression2>");
        out.println(
            "Proves whether two CEL expressions are semantically identical for all inputs.");
        out.println(
            "If not equivalent, outputs a counterexample showing inputs where they diverge.");
        out.println();
        out.println("Use '<=>' as the recommended separator between expressions.");
        out.println();
        out.println("Examples:");
        out.println("  cel-verifier> equiv x > 10 <=> 10 < x");
        out.println("  cel-verifier> equiv (a && b) || (a && c) <=> a && (b || c)");
        out.println(
            "  cel-verifier> equiv string_int_map == {'a': 1, 'b': 2} ? string_int_map.all(k, k =="
                + " 'a') : true <=> string_int_map == {'a': 1, 'b': 2} ? string_int_map.all(k, k =="
                + " 'a') : true");
        break;
      default:
        out.println("REPL Commands:");
        out.println(
            "  :var <name> <type>          Declare variable (e.g. :var role string, :var m"
                + " map<string,int>)");
        out.println("  :unknown <identifier>       Mark identifier as unknown");
        out.println("  :timeout <seconds>          Set solver timeout (default: 10s)");
        out.println("  :unroll <limit>             Set comprehension unroll limit (default: 5)");
        out.println("  :vars                       List session variables & options");
        out.println("  :clear                      Reset session state");
        out.println(
            "  :help [command]             Display help message or specific command details");
        out.println("  :quit                       Exit REPL");
        out.println();
        out.println("Verification Queries:");
        out.println("  sat <expr>                  Check satisfiability");
        out.println("  valid <expr>                Check validity (always true)");
        out.println("  equiv <expr1> <=> <expr2>   Prove logical equivalence");
        out.println("  <expr>                      Check satisfiability (default)");
        out.println();
        out.println(
            "Type ':help <command>' (e.g. ':help var', ':help sat') for detailed usage and"
                + " examples.");
        break;
    }
  }

  private static ImmutableList<String> splitEquivQuery(String rest) {
    if (rest == null || rest.trim().isEmpty()) {
      return ImmutableList.of();
    }
    String input = rest.trim();
    if (input.contains(" <=> ")) {
      return ImmutableList.copyOf(input.split(" <=> ", 2));
    }
    if (input.contains("<=>")) {
      return ImmutableList.copyOf(input.split("<=>", 2));
    }
    return ImmutableList.of();
  }

  private static Optional<String> extractCommandArg(String line, String prefix) {
    if (Ascii.equalsIgnoreCase(line, prefix)) {
      return Optional.of("");
    }
    String prefixLower = Ascii.toLowerCase(prefix);
    String lineLower = Ascii.toLowerCase(line);
    if (lineLower.startsWith(prefixLower + " ") || lineLower.startsWith(prefixLower + "\t")) {
      return Optional.of(line.substring(prefix.length()).trim());
    }
    return Optional.empty();
  }
}
