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

package dev.cel.parser;

import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Beta;

/**
 * Factory class for producing light-weight {@link CelParser} instances and builders using the Pratt
 * parser.
 */
@Beta
public final class CelLiteParserFactory {

  /**
   * Configure a builder to construct a light-weight {@code CelParser} instance.
   *
   * <p>Note, the {@link CelOptions#current} are enabled by default with {@link
   * CelOptions#enablePrattParser()} explicitly set to {@code true}.
   */
  public static CelParserBuilder newLiteParserBuilder() {
    return LiteParserImpl.newBuilder();
  }

  private CelLiteParserFactory() {}
}
