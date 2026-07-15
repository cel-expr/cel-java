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

package dev.cel.verifier.axioms;

import com.google.common.collect.ImmutableList;
import dev.cel.common.annotations.Internal;

/** Registry for CEL standard Z3 axioms. */
@Internal
public final class CelZ3StandardAxioms {

  public static ImmutableList<CelZ3FunctionAxiom> functionAxioms() {
    return ImmutableList.<CelZ3FunctionAxiom>builder()
        .add(
            AddAxiom.INSTANCE,
            SubtractAxiom.INSTANCE,
            MultiplyAxiom.INSTANCE,
            DivideAxiom.INSTANCE,
            ModuloAxiom.INSTANCE,
            NegateAxiom.INSTANCE,
            MapInsertAxiom.INSTANCE,
            TypeAxiom.INSTANCE,
            InAxiom.INSTANCE,
            LessAxiom.INSTANCE,
            LessEqualsAxiom.INSTANCE,
            GreaterAxiom.INSTANCE,
            GreaterEqualsAxiom.INSTANCE,
            SizeAxiom.INSTANCE)
        .addAll(OptionalAxioms.ALL_AXIOMS)
        .addAll(StringAxioms.ALL_AXIOMS)
        .addAll(TypeConversionAxioms.ALL_AXIOMS)
        .build();
  }

  private CelZ3StandardAxioms() {}
}
