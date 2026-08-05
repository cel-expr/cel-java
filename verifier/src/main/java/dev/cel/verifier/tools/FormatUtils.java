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

import com.google.common.collect.ImmutableMap;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import java.util.Map;

/** Utilities for formatting verification output (ANSI text & JSON). */
final class FormatUtils {

  // ANSI Escape Codes for formatting text
  static final String ANSI_RESET = "\u001B[0m";
  static final String ANSI_BOLD = "\u001B[1m";
  static final String ANSI_GREEN = "\u001B[32m";
  static final String ANSI_RED = "\u001B[31m";
  static final String ANSI_YELLOW = "\u001B[33m";
  static final String ANSI_CYAN = "\u001B[36m";

  private FormatUtils() {}

  /** Formats a single CelVerificationResult for human-readable console display with ANSI color. */
  static String formatTextResult(CelVerificationResult result) {
    StringBuilder sb = new StringBuilder();
    String statusColor = getStatusColor(result.status());
    sb.append(statusColor)
        .append(ANSI_BOLD)
        .append("[")
        .append(result.status())
        .append("]")
        .append(ANSI_RESET);

    if (!result.message().isEmpty()) {
      sb.append(" ").append(result.message());
    }

    return sb.toString();
  }

  /** Formats policy invariant verification results for human-readable console display. */
  static String formatTextPolicyResults(
      String policyName, ImmutableMap<String, CelVerificationResult> results) {
    StringBuilder sb = new StringBuilder();
    sb.append(ANSI_BOLD)
        .append("Policy Invariant Verification for '")
        .append(policyName)
        .append("':\n")
        .append(ANSI_RESET);

    for (Map.Entry<String, CelVerificationResult> entry : results.entrySet()) {
      String id = entry.getKey();
      CelVerificationResult result = entry.getValue();
      String symbol = result.status() == VerificationStatus.VERIFIED ? "✓" : "✗";
      String color = getStatusColor(result.status());

      sb.append("  ")
          .append(color)
          .append(symbol)
          .append(" Invariant '")
          .append(id)
          .append("': ")
          .append(result.status())
          .append(ANSI_RESET);

      if (!result.message().isEmpty()) {
        sb.append("\n    ").append(result.message().replace("\n", "\n    "));
      }
      sb.append("\n");
    }
    return sb.toString().trim();
  }

  /** Formats a single CelVerificationResult as structured JSON. */
  static String formatJsonResult(CelVerificationResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"status\": \"").append(result.status()).append("\",\n");
    sb.append("  \"message\": \"").append(escapeJson(result.message())).append("\"\n");
    sb.append("}");
    return sb.toString();
  }

  /** Formats policy invariant verification results as structured JSON. */
  static String formatJsonPolicyResults(
      String policyName, ImmutableMap<String, CelVerificationResult> results) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"policyName\": \"").append(escapeJson(policyName)).append("\",\n");
    sb.append("  \"invariants\": [\n");

    int count = 0;
    for (Map.Entry<String, CelVerificationResult> entry : results.entrySet()) {
      count++;
      String id = entry.getKey();
      CelVerificationResult res = entry.getValue();
      sb.append("    {\n");
      sb.append("      \"id\": \"").append(escapeJson(id)).append("\",\n");
      sb.append("      \"status\": \"").append(res.status()).append("\",\n");
      sb.append("      \"message\": \"").append(escapeJson(res.message())).append("\"\n");
      sb.append("    }").append(count < results.size() ? "," : "").append("\n");
    }

    sb.append("  ]\n");
    sb.append("}");
    return sb.toString();
  }

  private static String getStatusColor(VerificationStatus status) {
    switch (status) {
      case VERIFIED:
        return ANSI_GREEN;
      case VIOLATED:
        return ANSI_RED;
      case INCONCLUSIVE:
        return ANSI_YELLOW;
    }
    return ANSI_RESET;
  }

  static String escapeJson(String input) {
    if (input == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(input.length() + 16);
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      switch (c) {
        case '\\':
          sb.append("\\\\");
          break;
        case '"':
          sb.append("\\\"");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
          break;
      }
    }
    return sb.toString();
  }
}
