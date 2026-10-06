# CEL-Java Runtime Planner Migration Guide

## Summary

CEL-Java features an enhanced runtime (program planner) that is faster, more
ergonomic, and extensible. This document guides you through migrating your
codebase to leverage the planner.

## Overview

The new runtime offers the following advantages over the legacy implementation:

-   **Significantly improved performance:** Up to 90% faster evaluation latency
    and 99% reduction in memory allocations when programs are cached.
-   **Supports parsed-only evaluation mode:** Evaluate expressions directly
    without requiring upfront type-checking.
-   **Extensible CEL runtime value support:** Dynamically customize runtime
    value representations via custom value providers.
-   **Evaluation over POJOs:** Seamless integration with standard Java objects
    using native type extensions.
-   **Actionable error messages:** Clearer and more precise runtime error
    descriptions.
-   **Specification conformance:** Resolves subtle conformance and correctness
    discrepancies against the CEL specification.

## API Changes

To opt in, swap your existing builder for `plannerRuntimeBuilder` or
`plannerCelBuilder`:

```java
// Runtime only
CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder().build();

// Parser, type-checker, and runtime included
Cel cel = CelFactory.plannerCelBuilder().build();
```

> **Note:** `plannerRuntimeBuilder` and `plannerCelBuilder` are the recommended
> builders for all new and existing workloads. The legacy builders
> (`standardCelBuilder`, `legacyCelBuilder`, `standardCelRuntimeBuilder`,
> `legacyCelRuntimeBuilder`) are deprecated.

If you are overriding `CelOptions`, ensure
`enableHeterogeneousNumericComparisons` is enabled, and remove any deprecated
options:

```java
CelOptions options = CelOptions.current()
    // Ensure this is explicitly toggled on (enabled by default in planner
    // builders)
    .enableHeterogeneousNumericComparisons(true)
    // Remove the following deprecated options if you have them set:
    // .enableUnsignedLongs(...)
    // .unwrapWellKnownTypesOnFunctionDispatch(...)
    // .enableTimestampEpoch(...)
    .build();

CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder()
    .setOptions(options)
    .build();
```

For most setups, simply swapping the builder is all that is required. To get the
most out of the new runtime—and to troubleshoot any potential test
failures—please read through the **Enhancements** and **Behavioral Changes**
below.

## Enhancements

### Performance

If `CelRuntime.Program` is cached, you will observe significant improvements in
both latency and memory usage. This setup provides up to a 90% speedup and 99%
memory reduction:

```java
private static final CelCompiler CEL_COMPILER =
    CelCompilerFactory.standardCelCompilerBuilder().build();
private static final CelRuntime CEL_RUNTIME =
    CelRuntimeFactory.plannerRuntimeBuilder().build();

// Create and store once -- evaluate multiple times against different inputs.
private CelRuntime.Program program;

void compile(String expression) throws CelValidationException {
  CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
  this.program = CEL_RUNTIME.createProgram(ast);
}

Object eval(Map<String, ?> variableMap) throws CelEvaluationException {
  return program.eval(variableMap);
}
```

If `CelRuntime.Program` is created and evaluated on the same execution path, you
will still observe notable performance improvements for non-protobuf operations.
For expressions involving protobuf messages or field selections, performance
remains largely at parity.

```java
private CelAbstractSyntaxTree ast;

void compile(String expression) throws CelValidationException {
  this.ast = CEL_COMPILER.compile(expression).getAst();
}

Object createProgramThenEval(Map<String, ?> variableMap)
    throws CelEvaluationException {
  CelRuntime.Program program = CEL_RUNTIME.createProgram(ast);
  return program.eval(variableMap);
}
```

### Planning Step Validation

In the legacy runtime, `createProgram` was essentially a no-op. The new planner
runtime uses this step to perform upfront validation, catching setup issues
early and reducing unexpected evaluation failures:

```java
private static final CelRuntime CEL_RUNTIME =
    CelRuntimeFactory.plannerRuntimeBuilder()
        // Forgot to add function bindings here!
        .build();

private CelRuntime.Program program;

void compile(String expression) throws Exception {
  CelAbstractSyntaxTree ast = CEL_COMPILER.compile("my_custom_func()").getAst();

  // In the legacy runtime, an unbound function would only fail during eval().
  // The planner runtime will throw an exception right here during
  // createProgram.
  // Use it to catch and fix setup issues before caching the program.
  this.program = CEL_RUNTIME.createProgram(ast);
}
```

### Parsed-Only Evaluation Mode

You can now evaluate expressions without type-checking. This is useful if it is
infeasible to declare needed variables in advance, or if your expression relies
purely on dynamic types, and you would like to avoid type-checking overhead:

```java
private static final CelParser CEL_PARSER =
    CelParserFactory.standardCelParserBuilder().build();
private static final CelRuntime CEL_RUNTIME =
    CelRuntimeFactory.plannerRuntimeBuilder().build();

Object parsedOnlyEval() throws Exception {
  CelAbstractSyntaxTree ast = CEL_PARSER.parse("a && b").getAst();
  CelRuntime.Program program = CEL_RUNTIME.createProgram(ast);
  // This was previously impossible without declaring identifiers in the
  // environment
  return program.eval(ImmutableMap.of("a", true, "b", false));
}
```

> **Note:** Evaluating parsed-only expressions is slower than evaluating
> type-checked counterparts. In most cases, we recommend type-checking
> expressions for both performance and correctness guarantees.

### O(N) Time and Space Complexity

With the planner, time and space complexity is guaranteed to be linear with
respect to input size for comprehensions involving both lists and maps. In the
legacy runtime, this was only guaranteed for lists and not for maps (such as in
two-variable comprehensions).

### Custom Value Provider

Previously, extending CEL's type system was strictly limited to compile time
(via a custom type provider). The new planner allows you to achieve similar
flexibility at runtime by designating a custom value provider to extend CEL's
value system dynamically:

```java
// Example expression:
// AuditableRecord{ssn: "123-45-6789"}.ssn

// Extend StructValue to intercept field access (e.g., for audit logging)
final class AuditableRecord extends StructValue<String, Map<String, Object>> {
  AuditableRecord(Map<String, Object> fields) {
    super(fields);
  }

  @Override
  public Object select(String field) {
    if ("ssn".equals(field)) {
      AuditLogger.log("Sensitive field accessed: " + field);
    }
    return super.select(field);
  }
}

final class AuditableRecordProvider implements CelValueProvider {
  @Override
  public Optional<Object> newValue(
      String structType, Map<String, Object> fields) {
    if ("AuditableRecord".equals(structType)) {
      return Optional.of(new AuditableRecord(fields));
    }
    return Optional.empty();
  }
}

CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder()
    .setValueProvider(new AuditableRecordProvider())
    .build();
```

### Seamless POJO Support (Native Type Extensions)

With the legacy runtime, only protobuf messages were supported for evaluating
structs. The planner supports the native type extension library for registering
native Java types (POJOs) to be used directly in CEL expressions. Refer to
the <!-- disableFinding(LINE_OVER_80) -->
[Native Types Documentation](extensions/src/main/java/dev/cel/extensions/README.md#native-types)
<!-- enableFinding(LINE_OVER_80) --> for details.

### Actionable Error Messages

Runtime error messages are generally more accurate and actionable:

```
// Expression:
[1].flatten(-1)

// Legacy Runtime Error:
Evaluation error: Function 'list_flatten_list_int' failed

// Planner Runtime Error:
Evaluation error: Function 'flatten' failed
```

### Asynchronous Function Evaluation

The planner runtime natively supports function-call-based asynchronous
evaluation via `CelFunctionBinding.fromAsync` and `Program.evalAsync`:

```java
CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder()
    .setAsyncExecutor(executorService)
    .addFunctionBindings(
        CelFunctionBinding.fromAsync(
            "fetch_user_role_string",
            String.class,
            userId -> userClient.fetchRoleAsync(userId)))
    .build();

CelRuntime.Program program = runtime.createProgram(ast);
ListenableFuture<Object> resultFuture =
    program.evalAsync(ImmutableMap.of("user_id", "alice"));
```

Independent async calls across branches and comprehensions are dispatched
concurrently, identical calls are automatically memoized within an evaluation
session, and unneeded in-flight calls are cancelled when logical operators
short-circuit. Concurrency limits, iteration caps, completion batching, and
lifecycle hooks are configurable via `CelAsyncDrainStrategy`,
`CelAsyncObserver`, and `CelAsyncEvaluationOptions`.

## Behavioral Changes

### Map Creation with Decimal Keys

Attempting to create a map with decimals as keys will now result in an error:

```
// Expression:
{1: "one", 1.0: "one point zero"}[1.0]

// Legacy Runtime:
"one point zero" // Depended on java.util.Map implementation details.
                 // Note that in CEL, 1 == 1.0.

// Planner Runtime:
Error
```

This corrects a bug in the legacy runtime that violated the CEL specification
regarding map key equivalence.

### CEL Unknowns

Historically, CEL-Java treated missing attributes as potentially unknown
variables, yielding a `CelUnknownSet`:

```java
// Legacy behavior:
CelAbstractSyntaxTree ast = CEL_COMPILER.compile("a && b").getAst();
CelRuntime.Program program = CEL_LEGACY_RUNTIME.createProgram(ast);

// Note that "b" is not provided in the activation
Object result = program.eval(ImmutableMap.of("a", true));
assertThat(result).isInstanceOf(CelUnknownSet.class);
```

This made it difficult to distinguish between an unintended misconfiguration and
an intentional unknown. In the planner runtime, this same expression will throw
an evaluation exception: **`No such attribute(s): 'b'`**.

If your intent is to treat "b" as an unknown, you must explicitly declare it
using `CelAttributePattern` and pass it via `PartialVars`:

```java
// Planner behavior:
CelAbstractSyntaxTree ast = CEL_COMPILER.compile("a && b").getAst();
CelRuntime.Program program = CEL_PLANNER_RUNTIME.createProgram(ast);

PartialVars input = PartialVars.of(
    ImmutableMap.of("a", true),
    CelAttributePattern.create("b") // Explicitly declare "b" as an unknown
);
Object result = program.eval(input);
assertThat(result).isInstanceOf(CelUnknownSet.class);
```

### Late Bound Functions

Late bound functions must be registered in the runtime environment via the
`addLateBoundFunctions` builder method on `CelRuntimeBuilder`:

```java
CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder()
    .addLateBoundFunctions("record")
    .build();
```

### Lists and Maps as Evaluated Results

While the legacy runtime evaluated lists and maps into mutable
`java.util.ArrayList` or `java.util.LinkedHashMap` objects, the planner strictly
returns immutable variants (such as Guava's `ImmutableList` or `ImmutableMap`).

### Attribute-Level Async Resolution (CelAsyncRuntime)

The legacy `CelAsyncRuntime` drove async evaluation by intercepting unknown
attribute patterns (e.g., `user.address` or `items[0]`) via `UnknownContext` and
re-evaluating. The planner runtime intentionally does not support the legacy
`CelAsyncRuntime` / `UnknownContext.withResolvedAttributes` workflow for
injecting resolved values at arbitrary sub-attribute or index paths.

The recommended path forward is to use `PartialVars` to perform iterative
evaluation via unknowns out of band, or use asynchronous functions
(`CelFunctionBinding.fromAsync`), which is significantly more expressive,
performant, and ergonomic for I/O.
