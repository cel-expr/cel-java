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

package codelab;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.OpaqueValue;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exercise10 demonstrates how to extend the CEL type system with custom opaque types.
 *
 * <p>Given a user-supplied IPv4 address string {@code ip}, compile and evaluate CEL expressions
 * using two custom opaque types: {@code net.Address} and {@code net.AddressMatcher}.
 */
final class Exercise10 {
  private static final OpaqueType ADDRESS_TYPE = OpaqueType.create("net.Address");
  private static final OpaqueType ADDRESS_MATCHER_TYPE = OpaqueType.create("net.AddressMatcher");

  private static final CelTypeProvider NETWORK_TYPE_PROVIDER =
      new CelTypeProvider() {
        @Override
        public ImmutableList<CelType> types() {
          return ImmutableList.of(ADDRESS_TYPE, ADDRESS_MATCHER_TYPE);
        }

        @Override
        public Optional<CelType> findType(String typeName) {
          if (typeName.equals(ADDRESS_TYPE.name())) {
            return Optional.of(ADDRESS_TYPE);
          }
          if (typeName.equals(ADDRESS_MATCHER_TYPE.name())) {
            return Optional.of(ADDRESS_MATCHER_TYPE);
          }
          return Optional.empty();
        }
      };

  /**
   * Compiles the input expression using the custom network types and functions.
   *
   * @throws CelValidationException If the expression contains parsing or type-checking errors.
   */
  CelAbstractSyntaxTree compile(String expression) throws CelValidationException {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setResultType(SimpleType.BOOL)
            // Declare the input `ip` variable, custom types, and network functions here.
            .build();

    return celCompiler.compile(expression).getAst();
  }

  /**
   * Evaluates the compiled AST with the provided parameter values.
   *
   * @throws CelEvaluationException If evaluation fails (for example, on an invalid IPv4 address).
   */
  Object eval(CelAbstractSyntaxTree ast, Map<String, ?> parameterValues)
      throws CelEvaluationException {
    CelRuntime celRuntime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            // Register the custom network types and function bindings here.
            .build();

    return celRuntime.createProgram(ast).eval(parameterValues);
  }

  /** Custom opaque value representing an IPv4 address (`net.Address`). */
  @Immutable
  static final class Address extends OpaqueValue {
    private static final Splitter OCTET_SPLITTER = Splitter.on('.');
    private final int ipv4;

    private Address(int ipv4) {
      this.ipv4 = ipv4;
    }

    static Address parse(String str) {
      List<String> parts = OCTET_SPLITTER.splitToList(str);
      if (parts.size() != 4) {
        throw new IllegalArgumentException("invalid address: " + str);
      }
      int ipv4 = 0;
      for (String part : parts) {
        int octet;
        try {
          octet = Integer.parseInt(part);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("invalid address: " + str, e);
        }
        if (octet < 0 || octet > 255) {
          throw new IllegalArgumentException("invalid address: " + str);
        }
        ipv4 = (ipv4 << 8) | octet;
      }
      return new Address(ipv4);
    }

    int ipv4() {
      return ipv4;
    }

    @Override
    public Object value() {
      return this;
    }

    @Override
    public OpaqueType celType() {
      return ADDRESS_TYPE;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Address)) {
        return false;
      }
      Address that = (Address) o;
      return this.ipv4 == that.ipv4;
    }

    @Override
    public int hashCode() {
      return Integer.hashCode(ipv4);
    }
  }

  /** Custom opaque value representing an IPv4 address range (`net.AddressMatcher`). */
  @Immutable
  static final class AddressMatcher extends OpaqueValue {
    private static final Splitter RANGE_SPLITTER = Splitter.on('-');
    private final int minIncl;
    private final int maxIncl;

    private AddressMatcher(int minIncl, int maxIncl) {
      this.minIncl = minIncl;
      this.maxIncl = maxIncl;
    }

    static AddressMatcher parse(String str) {
      List<String> parts = RANGE_SPLITTER.splitToList(str);
      if (parts.size() != 2) {
        throw new IllegalArgumentException("invalid address matcher: " + str);
      }
      Address min = Address.parse(parts.get(0));
      Address max = Address.parse(parts.get(1));
      if (Integer.compareUnsigned(min.ipv4(), max.ipv4()) > 0) {
        throw new IllegalArgumentException("invalid address matcher: " + str);
      }
      return new AddressMatcher(min.ipv4(), max.ipv4());
    }

    boolean containsAddress(Address address) {
      return Integer.compareUnsigned(address.ipv4(), minIncl) >= 0
          && Integer.compareUnsigned(address.ipv4(), maxIncl) <= 0;
    }

    @Override
    public Object value() {
      return this;
    }

    @Override
    public OpaqueType celType() {
      return ADDRESS_MATCHER_TYPE;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof AddressMatcher)) {
        return false;
      }
      AddressMatcher that = (AddressMatcher) o;
      return this.minIncl == that.minIncl && this.maxIncl == that.maxIncl;
    }

    @Override
    public int hashCode() {
      return Objects.hash(minIncl, maxIncl);
    }
  }
}
