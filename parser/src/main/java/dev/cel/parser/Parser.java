// Copyright 2022 Google LLC
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

package dev.cel.parser;

import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationResult;

/**
 * Parses a CEL expression and returns an abstract syntax tree.
 *
 * <p>Dispatches to {@link AntlrParser} or {@link PrattParser} based on {@link
 * CelOptions#enablePrattParser()}.
 */
final class Parser {

  static CelValidationResult parse(CelParserImpl parser, CelSource source, CelOptions options) {
    if (options.enablePrattParser()) {
      return PrattParser.parse(source, options, parser.getMacros());
    }
    return AntlrParser.parse(source, options, parser.getMacros().values());
  }

  private Parser() {}
}
