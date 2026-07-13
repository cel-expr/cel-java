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

package dev.cel.runtime;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Timestamp;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.testing.BaseInterpreterTest;
import java.util.Arrays;
import java.util.Objects;
import org.junit.Test;

public abstract class AbstractPlannerInterpreterTest extends BaseInterpreterTest {

  @Override
  public void optional_errors() {
    // Exercised in planner_optional_errors instead
    skipBaselineVerification();
  }

  @Test
  public void planner_optional_errors() {
    source = "optional.unwrap([dyn(1)])";
    runTest(ImmutableMap.of());
  }

  @Override
  public void unknownField() {
    // Exercised in planner_unknownFieldSelection instead
    skipBaselineVerification();
  }

  @Override
  public void unknownResultSet() {
    // Exercised in planner_unknownResultSet_success instead
    skipBaselineVerification();
  }

  @Test
  public void planner_unknownFieldSelection() {
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));

    CelAttributePattern patternX = CelAttributePattern.fromQualifiedIdentifier("x");

    source = "x";
    // We have the full message, but we're claiming that the attribute is unknown.
    runTest(ImmutableMap.of("x", TestAllTypes.getDefaultInstance()), patternX);
    // A "partially known message". The result is still an unknown.
    runTest(
        ImmutableMap.of("x", TestAllTypes.getDefaultInstance()),
        CelAttributePattern.fromQualifiedIdentifier("x.single_int32"));

    source = "x.single_int32";
    runTest(ImmutableMap.of(), patternX);
    runTest(ImmutableMap.of(), CelAttributePattern.fromQualifiedIdentifier("x.single_int32"));

    source = "x.map_int32_int64[22]";
    runTest(ImmutableMap.of(), patternX);
    runTest(ImmutableMap.of(), CelAttributePattern.fromQualifiedIdentifier("x.map_int32_int64"));

    source = "x.repeated_nested_message[1]";
    runTest(ImmutableMap.of(), patternX);
    runTest(
        ImmutableMap.of(),
        CelAttributePattern.fromQualifiedIdentifier("x.repeated_nested_message"));

    source = "x.single_nested_message.bb";
    runTest(ImmutableMap.of(), patternX);
    runTest(
        ImmutableMap.of(),
        CelAttributePattern.fromQualifiedIdentifier("x.single_nested_message.bb"));

    source = "{1: x.single_int32}";
    runTest(ImmutableMap.of(), patternX);
    runTest(ImmutableMap.of(), CelAttributePattern.fromQualifiedIdentifier("x.single_int32"));

    source = "[1, x.single_int32]";
    runTest(ImmutableMap.of(), patternX);
    runTest(ImmutableMap.of(), CelAttributePattern.fromQualifiedIdentifier("x.single_int32"));
  }

  @Test
  public void planner_unknownResultSet_success() {
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    TestAllTypes message =
        TestAllTypes.newBuilder()
            .setSingleString("test")
            .setSingleTimestamp(Timestamp.newBuilder().setSeconds(15))
            .build();
    ImmutableMap<String, ?> variables = ImmutableMap.of("x", message);
    CelAttributePattern unknownInt32 =
        CelAttributePattern.fromQualifiedIdentifier("x.single_int32");
    CelAttributePattern unknownInt64 =
        CelAttributePattern.fromQualifiedIdentifier("x.single_int64");

    source = "x.single_int32 == 1 && true";
    runTest(variables, unknownInt32);

    source = "x.single_int32 == 1 && false";
    runTest(variables, unknownInt32);

    source = "x.single_int32 == 1 && x.single_int64 == 1";
    runTest(variables, unknownInt32, unknownInt64);

    source = "true && x.single_int32 == 1";
    runTest(variables, unknownInt32);

    source = "false && x.single_int32 == 1";
    runTest(variables, unknownInt32);

    source = "x.single_int32 == 1 || x.single_string == \"test\"";
    runTest(variables, unknownInt32);

    source = "x.single_int32 == 1 || x.single_string != \"test\"";
    runTest(variables, unknownInt32);

    source = "x.single_int32 == 1 || x.single_int64 == 1";
    runTest(variables, unknownInt32, unknownInt64);

    source = "true || x.single_int32 == 1";
    runTest(variables, unknownInt32);

    source = "false || x.single_int32 == 1";
    runTest(variables, unknownInt32);

    // dispatch test
    declareFunction(
        "f", memberOverload("f", Arrays.asList(SimpleType.INT, SimpleType.INT), SimpleType.BOOL));
    celRuntime =
        newBaseRuntimeBuilder(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .enableOptionalSyntax(true)
                    .comprehensionMaxIterations(1_000)
                    .build())
            .addFunctionBindings(
                CelFunctionBinding.from("f", Integer.class, Integer.class, Objects::equals))
            .setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()))
            .build();

    source = "x.single_int32.f(1)";
    runTest(variables, unknownInt32);

    source = "1.f(x.single_int32)";
    runTest(variables, unknownInt32);

    source = "x.single_int64.f(x.single_int32)";
    runTest(variables, unknownInt32, unknownInt64);

    source = "[0, 2, 4].exists(z, z == 2 || z == x.single_int32)";
    runTest(variables, unknownInt32);

    source = "[0, 2, 4].exists(z, z == x.single_int32)";
    runTest(variables, unknownInt32);

    source =
        "[0, 2, 4].exists_one(z, z == 0 || (z == 2 && z == x.single_int32) "
            + "|| (z == 4 && z == x.single_int64))";
    runTest(variables, unknownInt32, unknownInt64);

    source = "[0, 2].all(z, z == 2 || z == x.single_int32)";
    runTest(variables, unknownInt32);

    source =
        "[0, 2, 4].filter(z, z == 0 || (z == 2 && z == x.single_int32) "
            + "|| (z == 4 && z == x.single_int64))";
    runTest(variables, unknownInt32, unknownInt64);

    source =
        "[0, 2, 4].map(z, z == 0 || (z == 2 && z == x.single_int32) "
            + "|| (z == 4 && z == x.single_int64))";
    runTest(variables, unknownInt32, unknownInt64);

    source = "x.single_int32 == 1 ? 1 : 2";
    runTest(variables, unknownInt32);

    source = "true ? x.single_int32 : 2";
    runTest(variables, unknownInt32);

    source = "true ? 1 : x.single_int32";
    runTest(variables, unknownInt32);

    source = "false ? x.single_int32 : 2";
    runTest(variables, unknownInt32);

    source = "false ? 1 : x.single_int32";
    runTest(variables, unknownInt32);

    source = "x.single_int64 == 1 ? x.single_int32 : x.single_int32";
    runTest(variables, unknownInt32, unknownInt64);

    source = "{x.single_int32: 2, 3: 4}";
    runTest(variables, unknownInt32);

    source = "{1: x.single_int32, 3: 4}";
    runTest(variables, unknownInt32);

    source = "{1: x.single_int32, x.single_int64: 4}";
    runTest(variables, unknownInt32, unknownInt64);

    source = "[1, x.single_int32, 3, 4]";
    runTest(variables, unknownInt32);

    source = "[1, x.single_int32, x.single_int64, 4]";
    runTest(variables, unknownInt32, unknownInt64);

    source = "TestAllTypes{single_int32: x.single_int32}.single_int32 == 2";
    runTest(variables, unknownInt32);

    source = "TestAllTypes{single_int32: x.single_int32, single_int64: x.single_int64}";
    runTest(variables, unknownInt32, unknownInt64);

    clearAllDeclarations();
    declareVariable("unknown_list", ListType.create(SimpleType.INT));
    source = "unknown_list.map(x, x)";
    runTest(variables, CelAttributePattern.fromQualifiedIdentifier("unknown_list"));

    clearAllDeclarations();
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    source = "cel.bind(x, [1, 2, 3], 1 in x)";
    runTest(variables, CelAttributePattern.fromQualifiedIdentifier("x"));
  }

  @Test
  public void planner_unknownResultSet_errors() {
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    TestAllTypes message =
        TestAllTypes.newBuilder()
            .setSingleString("test")
            .setSingleTimestamp(Timestamp.newBuilder().setSeconds(15))
            .build();
    ImmutableMap<String, ?> variables = ImmutableMap.of("x", message);
    CelAttributePattern unknownInt32 =
        CelAttributePattern.fromQualifiedIdentifier("x.single_int32");

    source = "x.single_int32 == 1 && x.single_timestamp <= timestamp(\"bad timestamp string\")";
    runTest(variables, unknownInt32);

    source = "x.single_timestamp <= timestamp(\"bad timestamp string\") && x.single_int32 == 1";
    runTest(variables, unknownInt32);

    source =
        "x.single_timestamp <= timestamp(\"bad timestamp string\") "
            + "&& x.single_timestamp > timestamp(\"another bad timestamp string\")";
    runTest(variables, unknownInt32);

    source = "x.single_int32 == 1 || x.single_timestamp <= timestamp(\"bad timestamp string\")";
    runTest(variables, unknownInt32);

    source = "x.single_timestamp <= timestamp(\"bad timestamp string\") || x.single_int32 == 1";
    runTest(variables, unknownInt32);

    source =
        "x.single_timestamp <= timestamp(\"bad timestamp string\") "
            + "|| x.single_timestamp > timestamp(\"another bad timestamp string\")";
    runTest(variables, unknownInt32);

    source = "x";
    runTest(ImmutableMap.of(), CelAttributePattern.fromQualifiedIdentifier("x"));
  }
}
