# CEL-Java Codelab

In this codelab developers would learn some key concepts, how to set up, use variables, customize and build CEL for Java.

## What is CEL?

Common Expression Language (CEL) is an expression language that’s fast, portable, and safe to execute in performance-critical applications. CEL is designed to be embedded in an application, with application-specific extensions, and is ideal for extending declarative configurations that your applications might already use.

## What is covered in this Codelab?

In this codelab, we’ll walk through some coding Exercises that cover common use cases. This codelab is aimed at developers who would like to learn CEL to use services that already support CEL. This codelab doesn't cover how to integrate CEL into your own project. For a more in-depth look at the language, semantics, and features see the [CEL Language Definition on GitHub](https://github.com/google/cel-spec).

Some key areas covered are:

* [Hello, World: Using CEL to evaluate a String](#hello-world)
* [Creating variables](#creating-variables)
* [Commutatibe logical AND/OR](#logical-andor)
* [Adding custom functions](#custom-functions)
* [Building JSON](#building-json)
* [Building Protos](#building-protos)
* [Macros](#macros)
* [Static AST Validators and Optimizers](#static-ast-validators-and-optimizers)
* [Custom AST Validation](#custom-ast-validation)

### Prerequisites
This codelab builds upon a basic understanding of Protocol Buffers and Java.

If you're not familiar with Protocol Buffers, the first exercise will give you a sense of how CEL works, but because the more advanced examples use Protocol Buffers as the input into CEL, they may be harder to understand. Consider working through one of these [tutorials](https://developers.google.com/protocol-buffers/docs/tutorials?authuser=0), first.

Note that Protocol Buffers are not required to use CEL, but they are used extensively in this codelab.

What you'll need:

- JDK 8 or higher
- Bazel

You can test that java and bazel is installed by running:

```
java --version
bazel
```

## GitHub Setup

GitHub Repo:

The code for this codelab lives in the `codelab` folder of the cel-java repo. The solution is available in the `codelab/solution` folder of the same repo.

Clone and cd into the repo:

```
git clone git@github.com:cel-expr/cel-java.git
cd cel-java
```

Run the following:
```
bazel test //codelab/src/test/codelab:Exercise1Test --test_output=errors
```

You should see five failing tests in the output:

```
There were 5 failures:

… (omitted)

FAILURES!!!
Tests run: 5,  Failures: 5
```

## Where are CEL-Java Codelab Code in GitHub

Each exercise is laid out as `ExerciseN.java` and is accompanied by failing tests. Throughout this codelab, we will modify the main exercise code to make these tests pass.

- Codelab code: https://github.com/cel-expr/cel-java/tree/main/codelab/src/main/codelab
- Test code for the main codelab: https://github.com/cel-expr/cel-java/tree/main/codelab/src/test/codelab
- Codelab solution code: https://github.com/cel-expr/cel-java/tree/main/codelab/src/main/codelab/solutions
- Test code for the solution: https://github.com/cel-expr/cel-java/tree/main/codelab/src/test/codelab/solutions

We will also be using `google.rpc.context.AttributeContext` in [attribute_context.proto](https://github.com/googleapis/googleapis/blob/master/google/rpc/context/attribute_context.proto) to help with defining inputs for exercises.

## Hello, World

In the tried and true tradition of all programming languages, let's start with "Hello, World!".

Open `Exercise1.java`. You should see two stub methods:

```java
final class Exercise1 {
  /**
   * Compile the input {@code expression} and produce an AST. This method parses and type-checks the
   * given expression to validate the syntax and type-agreement of the expression.
   *
   * @throws IllegalArgumentException If the expression is malformed due to syntactic or semantic
   *     errors.
   */
  @SuppressWarnings("DoNotCallSuggester")
  CelAbstractSyntaxTree compile(String expression) {
    throw new UnsupportedOperationException("To be implemented");
  }

  /**
   * Evaluates the compiled AST.
   *
   * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
   */
  @SuppressWarnings("DoNotCallSuggester")
  Object eval(CelAbstractSyntaxTree ast) {
    throw new UnsupportedOperationException("To be implemented");
  }
}
```

### Setup the Environment

CEL applications evaluate an expression against an Environment.

The standard CEL environment supports all of the types, operators, functions, and macros defined within the language spec. The environment can be customized by providing options to disable macros, declare custom variables and functions, etc.
Java compilation and evaluation environments are constructed by their factories.

```java
// Setup an environment for expression compilation and evaluation.

// Breaking behavior changes and optional features are controlled by CelOptions
private static final CelOptions CEL_OPTIONS = CelOptions.current().build();

// CelCompiler is used to parse and type-check expressions.
// Note, it is preferable to statically initialize the compiler when it is
// statically configured.
// All CelCompiler instances are immutable and thus trivially thread-safe and
// thus amenable to caching.
private static final CelCompiler CEL_COMPILER =
        CelCompilerFactory.standardCelCompilerBuilder()
             .setOptions(CEL_OPTIONS)
             .build();

// CelRuntime takes in a compiled expression and produces an evaluable instance.
// CelRuntime can also be initialized statically and cached just like the
// compiler.
private static final CelRuntime CEL_RUNTIME =
        CelRuntimeFactory.plannerRuntimeBuilder()
             .setOptions(CEL_OPTIONS)
             .build();
```

Both `CelCompilerBuilder` and `CelRuntimeBuilder` allow customization through their fluent APIs.

### Parse and Check the Expression

Once the environment has been configured, expressions can be parsed and checked.

Copy the following into compile method:

```java
CelAbstractSyntaxTree compile(String expression) {
   // Construct a CelCompiler instance.
   // CelCompiler is immutable and when statically configured can be moved to a static final
   // member.
   CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

   CelAbstractSyntaxTree ast;
   try {
        // Parse the expression
        ast = celCompiler.parse(expression).getAst();
   } catch (CelValidationException e) {
        // Report syntactic errors, if present
        throw new IllegalArgumentException(
            "Failed to parse expression. Reason: " + e.getMessage(), e);
   }

   return ast;
}
```

When the expression is syntactically valid, the result of parse call is an executable **CelAbstractSyntaxTree**. Any issues from a malformed expression are raised as an exception of type **CelValidationException**.

You can type-check the parsed AST by calling check. Append the following at the end of the compile method:

```java
try {
    // Type-check the expression for correctness
    ast = celCompiler.check(ast).getAst();
} catch (CelValidationException e) {
    // Report semantic errors, if present.
    throw new IllegalArgumentException(
         "Failed to type-check expression. Reason: " + e.getMessage(), e);
}
return ast;
```

Type-checking issues are also raised via **CelValidationException** similar to the parse call. **e.getMessage()** contains a debug string that renders syntactic and semantic error messages in a human-readable format.

In this exercise, we expect the evaluated expression to always produce a string output. We can leverage the type-checker to validate this.

Append `setResultType` method to the CelCompiler declaration:

```java
// Construct a CelCompiler instance.
// CelCompiler is immutable and when statically configured can be moved to a static final
// member.
// "String" is the expected output type for the type-checked expression
CelCompiler celCompiler =
   CelCompilerFactory.standardCelCompilerBuilder()
       .setResultType(SimpleType.STRING)
       .build();
```

Run the unit tests again. You should now see all compile tests pass:

```
bazel test --test_output=errors //codelab/src/test/codelab:Exercise1Test
```

CelCompiler offers a `Compile` method where parse and check phases are consolidated. This will be used throughout the remainder of the exercises.

### Evaluate

After the expressions have been parsed and checked into an AST representation, it can be converted into an evaluable program whose function bindings and evaluation modes can be customized depending on the stack you are using.

Once a CEL expression is planned, it can be evaluated against an evaluation context (an activation). The evaluation result will be either a value or an error state.

Copy the following into eval method:

```java
/**
 * Evaluates the compiled AST.
 *
 * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
 */
Object eval(CelAbstractSyntaxTree ast) {
  // Construct a CelRuntime instance
  // CelRuntime is immutable just like the compiler and can be moved to a static final member.
  CelRuntime celRuntime = CelRuntimeFactory.plannerRuntimeBuilder().build();

  try {
    // Plan the program
    CelRuntime.Program program = celRuntime.createProgram(ast);

    // Evaluate the program without any additional arguments.
    return program.eval();
  } catch (CelEvaluationException e) {
    // Report any evaluation errors, if present
    throw new IllegalArgumentException(
        "Evaluation error has occurred. Reason: " + e.getMessage(), e);
  }
}
```

**CelRuntime.Program** represents the planned expression and the method eval evaluates it against the input. Any evaluation errors are raised as an exception of type **CelEvaluationException**.

Run the unit tests again and everything should pass now.

## Creating variables

Most CEL applications will declare variables that can be referenced within expressions. Variables declarations specify a name and a type. A variable's type may either be a CEL builtin type, a protocol buffer well-known type, or any protobuf message type so long as its descriptor is also provided to CEL.

At runtime, the hosting program binds instances of variables to the evaluation context (using the variable name as a key).

Suppose you would like to test whether a user provided value is negative. We can write a simple expression for this: value < 0, where value is the variable to be declared.

Take a look at the evaluate_negativeExpression method in `Exercise2Test.java`:

```java
@Test
@TestParameters("{value: 5, expectedResult: false}")
@TestParameters("{value: -5, expectedResult: true}")
public void evaluate_negativeExpression(long value, boolean expectedResult) {
  CelAbstractSyntaxTree ast = exercise2.compile("value < 0", "value", SimpleType.INT);

  Object evaluatedResult = exercise2.eval(ast, ImmutableMap.of("value", value));
  assertThat(evaluatedResult).isEqualTo(expectedResult);
}
```

Let's write the code needed to pass this test. Copy the following into Exercise2.java:

```java
/**
 * Compiles the input expression with provided variable information.
 *
 * @throws IllegalArgumentException If the expression is malformed due to syntactic or semantic
 *     errors.
 */
CelAbstractSyntaxTree compile(String expression, String variableName, CelType variableType) {
  // Compile (parse + type-check) the expression
  // CelCompiler is immutable and when statically configured can be moved to a static final
  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .addVar(variableName, variableType)
          .setResultType(SimpleType.BOOL)
          .build();
  try {
    return celCompiler.compile(expression).getAst();
  } catch (CelValidationException e) {
    throw new IllegalArgumentException(
        "Failed to compile expression. Reason: " + e.getMessage(), e);
  }
}
```

The compiler's `addVar` method allows us to declare variables. Note that you must supply the type of the variable being declared. Supported CEL types can be found [here](https://github.com/google/cel-java/tree/main/common/src/main/java/dev/cel/common/types).

> [!TIP]
> Best practice: You may have noticed `addVar` has an overloaded method which accepts a proto based Type instead of the CEL-Java native CelType used in this example. While the two types are functionally equivalent, we recommend using the native types whenever possible.

Let's make the evaluation work now. Copy into the eval method:

```java
/**
 * Evaluates the compiled AST with the user provided parameter values.
 *
 * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
 */
Object eval(CelAbstractSyntaxTree ast, Map<String, ?> parameterValues) {
  CelRuntime celRuntime = CelRuntimeFactory.plannerRuntimeBuilder().build();
  try {
    CelRuntime.Program program = celRuntime.createProgram(ast);

    // Evaluate using the provided map as input variables (key: variableName, value:
    // variableValue)
    return program.eval(parameterValues);
  } catch (CelEvaluationException e) {
    // Report any evaluation errors, if present
    throw new IllegalArgumentException(
        "Evaluation error has occurred. Reason: " + e.getMessage(), e);
  }
}
```

Now run the unit tests. You should see evaluate_negativeExpressionTest pass:

```
bazel test --test_output=errors //codelab/src/test/codelab:Exercise2Test
```

You can also declare complex type variables such as a protocol buffer message. Suppose we would like to validate if an auth claim is set on a Request object.

Take a look at the test case `evaluate_requestAuthorization` in `Exercise2Test.java`:

```java
@Test
@TestParameters("{group: 'admin', expectedResult: true}")
@TestParameters("{group: 'users', expectedResult: false}")
public void evaluate_requestAuthorization(String group, boolean expectedResult) {
  CelAbstractSyntaxTree ast =
      exercise2.compile(
          "request.auth.claims.group == 'admin'",
          "request",
          StructTypeReference.create("google.rpc.context.AttributeContext.Request"));

  AttributeContext.Auth auth =
      AttributeContext.Auth.newBuilder()
          .setPrincipal("user:me@acme.co")
          .setClaims(
              Struct.newBuilder()
                  .putFields("group", Value.newBuilder().setStringValue(group).build()))
          .build();
  AttributeContext.Request request = AttributeContext.Request.newBuilder().setAuth(auth).build();

  Object evaluatedResult = exercise2.eval(ast, ImmutableMap.of("request", request));
  assertThat(evaluatedResult).isEqualTo(expectedResult);
}
```

You'll notice that for the declared variable name request, we are creating a type reference for Protocol buffer message by providing its fully qualified name `google.rpc.context.AttributeContext.Request`.

Run the unit test. You should see the following error:

> Failed to compile expression. Reason: ERROR: <input>:1:12: Message type resolution failure while referencing field 'auth'. Ensure that the descriptor for type 'google.rpc.context.AttributeContext.Request' was added to the environment | request.auth.claims.group == 'admin'

To use variables that refer to protobuf messages, the type-checker needs to also know the type descriptor. The type descriptor is a protobuf message that describes the field declarations of a protobuf message. Descriptors can be thought of as a reflection type. Descriptors are used within the type-checker to determine the field type references. Descriptors can either be stored and saved, or read from a protobuf message instance.

To add the needed descriptor, append `addMessageTypes` method to the `celCompiler` declaration:

```java
CelCompiler celCompiler =
   CelCompilerFactory.standardCelCompilerBuilder()
       .addVar(variableName, variableType)
       .addMessageTypes(AttributeContext.Request.getDescriptor())
       .setResultType(SimpleType.BOOL)
       .build();
```

Run the unit tests again. All test cases should pass now.

## Logical AND/OR

One of CEL's more unique features is its use of commutative logical operators. Either side of a conditional branch can short-circuit the evaluation, even in the face of errors or partial input.

We'll be fixing the tests to complete the expected truth table.

Run the tests. You should see failures for Logical AND and Ternary:

```
bazel test --test_output=errors //codelab/src/test/codelab:Exercise3Test
```

Take a look at the evaluate_logicalOrShortCircuits_success test in `Exercise3Test.java`.

```java
/**
 * Demonstrates CEL's unique feature of commutative logical operators.
 *
 * <p>If a logical operation can short-circuit a branch that results in an error, CEL evaluation
 * will return the logical result instead of propagating the error.
 */
@Test
@TestParameters("{expression: 'true || true', expectedResult: true}")
@TestParameters("{expression: 'true || false', expectedResult: true}")
@TestParameters("{expression: 'false || true', expectedResult: true}")
@TestParameters("{expression: 'false || false', expectedResult: false}")
@TestParameters("{expression: 'true || (1 / 0 > 2)', expectedResult: true}")
@TestParameters("{expression: '(1 / 0 > 2) || true', expectedResult: true}")
public void evaluate_logicalOrShortCircuits_success(String expression, boolean expectedResult) {
  Object evaluatedResult = exercise3.compileAndEvaluate(expression);

  assertThat(evaluatedResult).isEqualTo(expectedResult);
}
```

You'll note that despite the OR expression containing a branch where the evaluation results in an error, CEL still returns the expected result through short-circuiting.

Conversely, an exception is surfaced when all branches of the logical operator resolves to an error:

```java
/** Demonstrates a case where an error is surfaced to the user. */
@Test
@TestParameters("{expression: 'false || (1 / 0 > 2)'}")
@TestParameters("{expression: '(1 / 0 > 2) || false'}")
public void evaluate_logicalOrFailure_throwsException(String expression) {
  IllegalArgumentException exception =
      assertThrows(
          IllegalArgumentException.class, () -> exercise3.compileAndEvaluate(expression));

  assertThat(exception).hasMessageThat().contains("Evaluation error has occurred.");
  assertThat(exception).hasCauseThat().isInstanceOf(CelEvaluationException.class);
  assertThat(exception).hasCauseThat().hasMessageThat().contains("evaluation error: / by zero");
}
```

Using this knowledge, try to complete the truth table by filling in the expressions in @TestParameters for logicalAnd and ternary tests.

CEL finds an evaluation order which gives results whenever possible, ignoring errors or even missing data that might occur in other evaluation orders. Applications like IAM conditions rely on this property to minimize the cost of evaluation, deferring the gathering of expensive inputs when a result can be reached without them.

## Custom Functions

While there are many builtin functions, there are occasions where a custom function is useful:

- Improve the user experience for common conditions
- Expose context-sensitive state

In this exercise, we'll be exploring how to expose a function to package together commonly used checks. We'll introduce a '.contains' function for checking if a map has a key set and is set to a particular value, allowing for expressions like: `map.contains(key, value)`. This provides a generalization of this common pattern using built-ins for string maps: `has(map.key) && map.key == value`.

Take a look at the expression that has .contains function in `Exercise4Test.java`:

```java
@Test
@TestParameters("{group: 'admin', expectedResult: true}")
@TestParameters("{group: 'users', expectedResult: false}")
public void evaluate_requestContainsGroup_success(String group, boolean expectedResult) {
  CelAbstractSyntaxTree ast = exercise4.compile("request.auth.claims.contains('group', 'admin')");
  AttributeContext.Auth auth =
      AttributeContext.Auth.newBuilder()
          .setPrincipal("user:me@acme.co")
          .setClaims(
              Struct.newBuilder()
                  .putFields("group", Value.newBuilder().setStringValue(group).build()))
          .build();
  AttributeContext.Request request = AttributeContext.Request.newBuilder().setAuth(auth).build();

  Object evaluatedResult = exercise4.eval(ast, ImmutableMap.of("request", request));

  assertThat(evaluatedResult).isEqualTo(expectedResult);
}
```

We will build upon the provided compile and eval method implementations in Exercise4.java to make this work:

```java
final class Exercise4 {

/**
 * Compiles the input expression.
 *
 * @throws IllegalArgumentException If the expression is malformed due to syntactic or semantic
 *     errors.
 */
CelAbstractSyntaxTree compile(String expression) {
  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .addVar("request", StructTypeReference.create(Request.getDescriptor().getFullName()))
          .addMessageTypes(Request.getDescriptor())
          .setResultType(SimpleType.BOOL)
          // Provide the custom `contains` function declaration here.
          .build();

  try {
    return celCompiler.compile(expression).getAst();
  } catch (CelValidationException e) {
    throw new IllegalArgumentException("Failed to compile expression.", e);
  }
}

/**
 * Evaluates the compiled AST with the user provided parameter values.
 *
 * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
 */
Object eval(CelAbstractSyntaxTree ast, Map<String, ?> parameterValues) {
  CelRuntime celRuntime =
      CelRuntimeFactory.plannerRuntimeBuilder()
          // Provide the custom `contains` function implementation here.
          .build();

  try {
    CelRuntime.Program program = celRuntime.createProgram(ast);
    return program.eval(parameterValues);
  } catch (CelEvaluationException e) {
    throw new IllegalArgumentException("Evaluation error has occurred.", e);
  }
}
```

Run the test:

```
bazel test --test_output=errors //codelab/src/test/codelab:Exercise4Test
```

You should see the following error:

> ERROR: <input>:1:29: found no matching overload for 'contains' applied to 'map(string, dyn).(string, string)'
> | request.auth.claims.contains('group', 'admin')
> | ............................^

To fix the error, the contains function will need to be added to the list of declarations which currently declares the request variable. Declaring a function is not much different than declaring a variable. A function must indicate its common name and enumerate a set of overloads with unique signatures.

The following snippet shows how to declare a parameterized type. This is the most complicated any function overload  ever be for CEL:

```java
/**
 * Compiles the input expression.
 *
 * @throws IllegalArgumentException If the expression is malformed due to syntactic or semantic
 *     errors.
 */
CelAbstractSyntaxTree compile(String expression) {
  // Useful components of the type-signature for 'contains'.
  TypeParamType typeParamA = TypeParamType.create("A");
  TypeParamType typeParamB = TypeParamType.create("B");
  MapType mapTypeAB = MapType.create(typeParamA, typeParamB);

  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .addVar("request", StructTypeReference.create(Request.getDescriptor().getFullName()))
          .addMessageTypes(Request.getDescriptor())
          .setResultType(SimpleType.BOOL)
          .addFunctionDeclarations(
              newFunctionDeclaration(
                  "contains",
                  newMemberOverload(
                      "map_contains_key_value",
                      SimpleType.BOOL,
                      ImmutableList.of(mapTypeAB, typeParamA, typeParamB))))
          .build();

  try {
    return celCompiler.compile(expression).getAst();
  } catch (CelValidationException e) {
    throw new IllegalArgumentException("Failed to compile expression.", e);
  }
}
```

Re-run the exercise. Compilation should now succeed and you should see a new error about the missing runtime function:

> evaluation error at <input>:28: [internal] Unknown overload id 'map_contains_key_value' for function 'contains'

To fix this, we need to provide the CEL environment with a function implementation for `contains`.

Provide the function implementation to the runtime using the .addFunctionBindings builder method:

```java
/**
 * Evaluates the compiled AST with the user provided parameter values.
 *
 * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
 */
Object eval(CelAbstractSyntaxTree ast, Map<String, ?> parameterValues) {
  CelRuntime celRuntime =
      CelRuntimeFactory.plannerRuntimeBuilder()
          .addFunctionBindings(
              CelFunctionBinding.from(
                  "map_contains_key_value",
                  ImmutableList.of(Map.class, String.class, Object.class),
                  Exercise4::mapContainsKeyValue))
          .build();

  try {
    CelRuntime.Program program = celRuntime.createProgram(ast);
    return program.eval(parameterValues);
  } catch (CelEvaluationException e) {
    throw new IllegalArgumentException("Evaluation error has occurred.", e);
  }
}
```

A function binding requires an Overload implementation to be provided (the "actual Java code" to be executed). The method reference `Exercise4::mapContainskeyValue` is that implementation:

```java
/**
 * mapContainsKeyValue implements the custom function: map.contains(key, value) -> bool.
 *
 * @param args, where:
 *     <ol>
 *       <li>args[0] is the map
 *       <li>args[1] is the key
 *       <li>args[2] is the value at the key
 *     </ol>
 *
 * @return true If the key was found AND the value at the key equals to the value being checked
 */
@SuppressWarnings("unchecked") // Type-checker guarantees casting safety.
private static boolean mapContainsKeyValue(Object[] args) {
  // The declaration of the function ensures that only arguments which match
  // the mapContainsKey signature will be provided to the function.
  Map<String, Object> map = (Map<String, Object>) args[0];
  String key = (String) args[1];
  Object value = args[2];

  return map.containsKey(key) && map.containsValue(value);
}
```

> [!TIP]
> Best practice: Use `Unary<T>` or `Binary<T1, T2>` helper interfaces to improve compile-time correctness for any overload implementations with 2 arguments or fewer.

> [!TIP]
> Best practice: Declare overload ids according to their types and function names. e.g. targetType_func_argType_argType. In the case where argType is a type param, use a descriptive name instead of the simple type name.

## Building JSON

CEL can also produce non-boolean outputs, such as JSON.

Have a look at the test case in `Exercise5Test.java` first:

```java
@Test
public void evaluate_jwtWithTimeVariable_producesJsonString() throws Exception {
  // Note the quoted keys in the CEL map literal. For proto messages the field names are unquoted
  // as they represent well-defined identifiers.
  String jwt =
      "{'sub': 'serviceAccount:delegate@acme.co',"
          + "'aud': 'my-project',"
          + "'iss': 'auth.acme.com:12350',"
          + "'iat': time,"
          + "'nbf': time,"
          + "'exp': time + duration('300s'),"
          + "'extra_claims': {"
          + "'group': 'admin'"
          + "}}";
  CelAbstractSyntaxTree ast = exercise5.compile(jwt);

  // The output of the program is a map type.
  @SuppressWarnings("unchecked")
  Map<String, Object> evaluatedResult =
      (Map<String, Object>)
          exercise5.eval(ast, ImmutableMap.of("time", ProtoTimeUtils.fromSecondsToTimestamp(1698361778)));
  String jsonOutput = exercise5.toJson(evaluatedResult);

  assertThat(jsonOutput)
      .isEqualTo(
          "{\"sub\":\"serviceAccount:delegate@acme.co\","
              + "\"aud\":\"my-project\","
              + "\"iss\":\"auth.acme.com:12350\","
              + "\"iat\":\"2023-10-26T23:09:38Z\","
              + "\"nbf\":\"2023-10-26T23:09:38Z\","
              + "\"exp\":\"2023-10-26T23:14:38Z\","
              + "\"extra_claims\":{\"group\":\"admin\"}}");
}
```

Run the test:

```sh
bazel test --test_output=errors //codelab/src/test/codelab:Exercise5Test
```

You should see the following error:

```
There was 1 failure:
1) evaluate_jwtWithTimeVariable_producesJsonString(codelab.Exercise5Test)
java.lang.IllegalArgumentException: Failed to compile expression.
        at codelab.Exercise5.compile(Exercise5.java:49)
        at codelab.Exercise5Test.evaluate_jwtWithTimeVariable_producesJsonString(Exercise5Test.java:46)
        ... 26 trimmed
Caused by: dev.cel.common.CelValidationException: ERROR: <input>:1:99: undeclared reference to 'time' (in container '')
 | {'sub': 'serviceAccount:delegate@acme.co','aud': 'my-project','iss': 'auth.acme.com:12350','iat': time,'nbf': time,'exp': time + duration('300s'),'extra_claims': {'group': 'admin'}}
 | ..................................................................................................^
 ... and more ...
```

In `Exercise5.java`, add a declaration for the `time` variable of type `SimpleType.TIMESTAMP`:

```java
CelAbstractSyntaxTree compile(String expression) {
  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .addVar("time", SimpleType.TIMESTAMP)
          .build();
}
```

> [!NOTE]
> Timestamps and durations are well-known types and have special convenience
types within CEL. The `google.protobuf.Timestamp` and `google.protobuf.Duration`
message types are equivalent to the convenience types; however, the fields of
all well-known types are not directly accessible, but are instead mediated by
member functions.

The expression will successfully compile then evaluate, but you will run into a different error:

```
There was 1 failure:
1) evaluate_jwtWithTimeVariable_producesJsonString(codelab.Exercise5Test)
java.lang.UnsupportedOperationException: To be implemented
        at codelab.Exercise5.toJson(Exercise5.java:73)
        at codelab.Exercise5Test.evaluate_jwtWithTimeVariable_producesJsonString(Exercise5Test.java:52)
```

The evaluated result is a native Java map type, and this needs to be explicitly
converted to JSON by leveraging `google.protobuf.Struct`. The internal CEL
representation is JSON convertible as it only refers to types that JSON can
support or for which there is a well-known [Proto to JSON mapping](https://protobuf.dev/programming-guides/proto3/#json).

Copy and paste the following into `toJson` method:

```java
/** Converts the evaluated result into a JSON string using protobuf's google.protobuf.Struct. */
String toJson(Map<String, Object> map) throws InvalidProtocolBufferException {
  // Convert the map into google.protobuf.Struct using the CEL provided helper function
  Struct jsonStruct = CelProtoJsonAdapter.adaptToJsonStructValue(map);
  // Then use Protobuf's JsonFormat to produce a JSON string output.
  return JsonFormat.printer().omittingInsignificantWhitespace().print(jsonStruct);
}
```
Re-running the test will show that it successfully passes.

## Building Protos

CEL can also build protobuf messages for any message type compiled into the application.

Have a look at the test case in `Exercise6Test.java` first:

```java
@Test
public void evaluate_constructAttributeContext() {
  // Given JSON web token and the current time as input variables,
  // Setup an expression to construct an AttributeContext protobuf object.
  //
  // Note: the field names within the proto message types are not quoted as they
  // are well-defined names composed of valid identifier characters. Also, note
  // that when building nested proto objects, the message name needs to prefix
  // the object construction.
  String expression =
      "Request{\n"
          + "auth: Auth{"
          + "  principal: jwt.iss + '/' + jwt.sub,"
          + "  audiences: [jwt.aud],"
          + "  presenter: 'azp' in jwt ? jwt.azp : '',"
          + "  claims: jwt"
          + "},"
          + "time: now"
          + "}";
  // Values for `now` and `jwt` variables to be passed into the runtime
  Timestamp now = ProtoTimeUtils.now();
  ImmutableMap<String, Object> jwt =
      ImmutableMap.of(
          "sub", "serviceAccount:delegate@acme.co",
          "aud", "my-project",
          "iss", "auth.acme.com:12350",
          "extra_claims", ImmutableMap.of("group", "admin"));
  AttributeContext.Request expectedMessage =
      AttributeContext.Request.newBuilder()
          .setTime(now)
          .setAuth(
              AttributeContext.Auth.newBuilder()
                  .setPrincipal("auth.acme.com:12350/serviceAccount:delegate@acme.co")
                  .addAudiences("my-project")
                  .setClaims(
                      Struct.newBuilder()
                          .putAllFields(
                              ImmutableMap.of(
                                  "sub", newStringValue("serviceAccount:delegate@acme.co"),
                                  "aud", newStringValue("my-project"),
                                  "iss", newStringValue("auth.acme.com:12350")))
                          .putFields(
                              "extra_claims",
                              Value.newBuilder()
                                  .setStructValue(
                                      Struct.newBuilder()
                                          .putFields("group", newStringValue("admin"))
                                          .build())
                                  .build())))
          .build();

  // Compile the `Request` message construction expression and validate that
  // the resulting expression type matches the fully qualified message name.
  CelAbstractSyntaxTree ast = exercise6.compile(expression);
  AttributeContext.Request evaluatedResult =
      (AttributeContext.Request)
          exercise6.eval(
              ast,
              ImmutableMap.of(
                  "now", now,
                  "jwt", jwt));

  assertThat(evaluatedResult).isEqualTo(expectedMessage);
}
```

Run the test:

```sh
bazel test --test_output=errors //codelab/src/test/codelab:Exercise6Test
```

You should see the following error:

```
There was 1 failure:
1) evaluate_constructAttributeContext(codelab.Exercise6Test)
java.lang.IllegalArgumentException: Failed to compile expression.
        at codelab.Exercise6.compile(Exercise6.java:42)
        at codelab.Exercise6Test.evaluate_constructAttributeContext(Exercise6Test.java:72)
        ... 26 trimmed
Caused by: dev.cel.common.CelValidationException: ERROR: <input>:1:8: undeclared reference to 'Request' (in container '')
 | Request{
 | .......^
... and many more ...
```

The container is basically the equivalent of a namespace or package, but can
even be as granular as a protobuf message name. CEL containers use the same
namespace resolution rules as [Protobuf and C++][27] for determining where a
given variable, function, or type name is declared.

Given the container `google.rpc.context.AttributeContext` the type-checker and
the evaluator will try the following identifier names for all variables, types,
and functions:

*   `google.rpc.context.AttributeContext.<id>`
*   `google.rpc.context.<id>`
*   `google.rpc.<id>`
*   `google.<id>`
*   `<id>`

For absolute names, prefix the variable, type, or function reference with a
leading dot `.`. In the example, the expression `.<id>` will only search for the
top-level `<id>` identifier without first checking within the container.

Try specifying the `.setContainer("google.rpc.context.AttributeContext")` option
to the compiler environment then run the test again:

```java
CelAbstractSyntaxTree compile(String expression) {
  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setContainer("google.rpc.context.AttributeContext")
           // Declare variables for "jwt" and "now" here
          .addMessageTypes(Request.getDescriptor())
          .setResultType(StructTypeReference.create(Request.getDescriptor().getFullName()))
          .build();
  ...
}
```

```
There was 1 failure:
1) evaluate_constructAttributeContext(codelab.Exercise6Test)
...
Caused by: dev.cel.common.CelValidationException: ERROR: <input>:2:25: undeclared reference to 'jwt' (in container 'google.rpc.context.AttributeContext')
 | auth: Auth{  principal: jwt.iss + '/' + jwt.sub,  audiences: [jwt.aud],  presenter: 'azp' in jwt ? jwt.azp : '',  claims: jwt},time: now}
 | ........................^
... and many more ...
```

We're making progress. Declare the `jwt` and `now` variables:

```java
CelAbstractSyntaxTree compile(String expression) {
  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setContainer("google.rpc.context.AttributeContext")
          .addVar("jwt", SimpleType.DYN)
          .addVar("now", SimpleType.TIMESTAMP)
          .addMessageTypes(Request.getDescriptor())
          .setResultType(StructTypeReference.create(Request.getDescriptor().getFullName()))
          .build();
  ...
}
```

The test should pass now.

> [!NOTE]
> Additional considerations for using CEL to build protos:
>
> 1.  There is no native support for the conditional assignment for `oneof`
>     fields.
> 2.  There are issues round-tripping to / from a `google.protobuf.Any`.
>
> When a `oneof` needs to be set, test whether the desired value is present before
> constructing the message, or extend CEL to include a custom object building
> function such as a [`wither`](https://crates.io/crates/withers_derive#the-wither-pattern)
> method, or perhaps something more abstract.
>
> When an `Any` contains a wrapper type such as `google.protobuf.IntValue`, CEL
> automatically unpacks the `Any` to `int` or `null_type` depending on the
> contents. In the case where the wrapper type is unset, the `null` could not be
> assigned to an `Any` field and have its same original meaning. So far, this is
> the only roundtripping issue we have discovered, but it's worth noting.

## Macros

Macros can be used to manipulate the CEL program at parse time. Macros match a
call signature and manipulate the input call and its arguments in order to
produce a new subexpression AST.

Macros can be used to implement complex logic in the AST that can't be written
directly in CEL. For example, the `has` macro enables field presence testing.
The comprehension macros such as `exists` and `all` replace a function call with
bounded iteration over an input list or map. Neither concept is possible at a
syntactic level, but they are possible through macro expansions.

Have a look at the test case in `Exercise7Test.java` first:

```java
@Test
public void evaluate_checkJwtClaimsWithMacro_evaluatesToTrue() {
  String expression =
      "jwt.extra_claims.exists(c, c.startsWith('group'))"
          + " && jwt.extra_claims"
          + ".filter(c, c.startsWith('group'))"
          + ".all(c, jwt.extra_claims[c]"
          + ".all(g, g.endsWith('@acme.co')))";
  ImmutableMap<String, Object> jwt =
      ImmutableMap.of(
          "sub",
          "serviceAccount:delegate@acme.co",
          "aud",
          "my-project",
          "iss",
          "auth.acme.com:12350",
          "extra_claims",
          ImmutableMap.of("group1", ImmutableList.of("admin@acme.co", "analyst@acme.co")),
          "labels",
          ImmutableList.of("metadata", "prod", "pii"),
          "groupN",
          ImmutableList.of("forever@acme.co"));
  CelAbstractSyntaxTree ast = exercise7.compile(expression);

  // Evaluate a complex-ish JWT with two groups that satisfy the criteria.
  // Output: true.
  boolean evaluatedResult = (boolean) exercise7.eval(ast, ImmutableMap.of("jwt", jwt));

  assertThat(evaluatedResult).isTrue();
}
```

Run the test:

```sh
bazel test --test_output=errors //codelab/src/test/codelab:Exercise7Test
```

You should see the following error:

```
There was 1 failure:
1) evaluate_checkJwtClaimsWithMacro_evaluatesToTrue(codelab.Exercise7Test)
java.lang.IllegalArgumentException: Failed to compile expression.
        at codelab.Exercise7.compile(Exercise7.java:40)
        at codelab.Exercise7Test.evaluate_checkJwtClaimsWithMacro_evaluatesToTrue(Exercise7Test.java:52)
        ... 26 trimmed
Caused by: dev.cel.common.CelValidationException: ERROR: <input>:1:24: undeclared reference to 'exists' (in container '')
 | jwt.extra_claims.exists(c, c.startsWith('group')) && jwt.extra_claims.filter(c, c.startsWith('group')).all(c, jwt.extra_claims[c].all(g, g.endsWith('@acme.co')))
 | .......................^
... and many more ...
```

Specify the option `setStandardMacros` with `CelStandardMacro.ALL`,
`CelStandardMacro.FILTER`, and `CelStandardMacro.EXISTS` as arguments:

```java
CelAbstractSyntaxTree compile(String expression) {
  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .addVar("jwt", SimpleType.DYN)
          .setStandardMacros(
              CelStandardMacro.ALL, CelStandardMacro.FILTER, CelStandardMacro.EXISTS)
          .setResultType(SimpleType.BOOL)
          .build();
  ...
}
```

Run the test again to confirm that it passes.

These are the currently supported macros:

| Macro        | Signature                 | Description                       |
| ------------ | ------------------------- | --------------------------------- |
| `all`        | `r.all(var, cond)`        | Test if `cond` evaluates `true` for *all* `var` in range `r`.
| `exists`     | `r.exists(var, cond)`     | Test if `cond` evaluates `true` for *any* `var` in range `r`.
| `exists_one` | `r.exists_one(var, cond)` | Test if `cond` evaluates `true` for *only one* `var` in range `r`.
| `filter`     | `r.filter(var, cond)`     | For lists, create a new list where each element `var` in range `r` satisfies the condition `cond`. For maps, create a new list where each key `var` in range `r` satisfies the condition `cond`.
| `map`        | `r.map(var, expr)`        | Create a new list where each each `var` in range `r` is transformed by `expr`.
|              | `r.map(var, cond, expr)`  | Same as two-arg `map` but with a conditional `cond` filter before the value is transformed.
| `has`        | `has(a.b)`                | Presence test for `b` on value `a` \: For maps, json tests definition. For protos, tests non-default primitive value or a or a set message field.

When the range `r` argument is a `map` type, the `var` will be the map key, and
for `list` type values the `var` will be the list element value. The `all`,
`exists`, `exists_one`, `filter`, and `map` macros perform an AST rewrite that
does for-each iteration which is bounded by the size of the input.

The bounded comprehensions ensure that CEL programs won't be Turing-complete,
but they evaluate in super-linear time with respect to the input. Use these
macros sparingly or not at all. Heavy use of comprehensions usually a good
indicator that a custom function would provide a better user experience and
better performance.

> [!TIP]
> Best practice: `CelStandardMacro.STANDARD_MACROS` enables all listed macros, but
it's safer to explicitly enable only the required ones for your use case.

## Static AST Validators and Optimizers

CEL can perform complex validations on a compiled AST beyond what the
type-checker is capable of. CEL can also enhance evaluation efficiency through
AST optimizations such as constant folding and common subexpression elimination.
We will explore the use of canonical CEL validators and optimizers available.

> [!NOTE]
> Note: Both validation and optimization require a type-checked AST.

> [!CAUTION]
> AST validation and optimization should not be done in latency critical
code paths, similar to parsing and type-checking.

### Validators

Inspect the first three test cases in `Exercise8Test.java`:

```java
@Test
public void validate_invalidTimestampLiteral_returnsError() throws Exception {
  CelAbstractSyntaxTree ast = exercise8.compile("timestamp('bad')");

  CelValidationResult validationResult = exercise8.validate(ast);

  assertThat(validationResult.hasError()).isTrue();
  assertThat(validationResult.getErrorString())
      .isEqualTo(
          "ERROR: <input>:1:11: timestamp validation failed. Reason: evaluation error: Text 'bad'"
              + " could not be parsed at index 0\n"
              + " | timestamp('bad')\n"
              + " | ..........^");
}

@Test
public void validate_invalidDurationLiteral_returnsError() throws Exception {
  CelAbstractSyntaxTree ast = exercise8.compile("duration('bad')");

  CelValidationResult validationResult = exercise8.validate(ast);

  assertThat(validationResult.hasError()).isTrue();
  assertThat(validationResult.getErrorString())
      .isEqualTo(
          "ERROR: <input>:1:10: duration validation failed. Reason: evaluation error: invalid"
              + " duration format\n"
              + " | duration('bad')\n"
              + " | .........^");
}

@Test
public void validate_invalidRegexLiteral_returnsError() throws Exception {
  CelAbstractSyntaxTree ast = exercise8.compile("'text'.matches('**')");

  CelValidationResult validationResult = exercise8.validate(ast);

  assertThat(validationResult.hasError()).isTrue();
  assertThat(validationResult.getErrorString())
      .isEqualTo(
          "ERROR: <input>:1:16: Regex validation failed. Reason: Dangling meta character '*' near"
              + " index 0\n"
              + "**\n"
              + "^\n"
              + " | 'text'.matches('**')\n"
              + " | ...............^");
}
```

Note that all three test cases contain an expression with invalid literals
that would fail if evaluated.

Run the validator tests (note the `--test_filter` flag):

```sh
bazel test --test_output=errors --test_filter=validate //codelab/src/test/codelab:Exercise8Test
```


You should see 4 tests failing:

```
There were 4 failures:
1) validate_invalidTimestampLiteral_returnsError(codelab.Exercise8Test)
... and more
FAILURES!!!
Tests run: 4,  Failures: 4
```

Setting up a validator requires an instance of a compiler and a runtime. These
have been provided for you out of convenience in `Exercise8.java`:

```java
private static final CelCompiler CEL_COMPILER =
    CelCompilerFactory.standardCelCompilerBuilder()
        .addVar("x", SimpleType.INT)
        .addVar(
            "request", StructTypeReference.create("google.rpc.context.AttributeContext.Request"))
        .addMessageTypes(AttributeContext.Request.getDescriptor())
        .build();
private static final CelRuntime CEL_RUNTIME =
    CelRuntimeFactory.plannerRuntimeBuilder()
        .addMessageTypes(AttributeContext.Request.getDescriptor())
        .build();
```

Copy and paste the following, below where the runtime is declared:

```java
// Just like the compiler and runtime, the validator and optimizer can be statically
// initialized as their instances are immutable.
private static final CelValidator CEL_VALIDATOR =
    CelValidatorFactory.standardCelValidatorBuilder(CEL_COMPILER, CEL_RUNTIME)
        .build();
```

Next, replace the implementation of `validate` method with the following code:

```java
/** Validates a type-checked AST. */
CelValidationResult validate(CelAbstractSyntaxTree checkedAst) {
  return CEL_VALIDATOR.validate(checkedAst);
}
```

Re-run the tests. The tests no longer throws an exception, but they still fail
because we aren't actually validating anything at the moment:

```
1) validate_invalidTimestampLiteral_returnsError(codelab.Exercise8Test)
value of: hasError()
expected to be true
        at codelab.Exercise8Test.validate_invalidTimestampLiteral_returnsError(Exercise8Test.java:28)
... and more
```

We now need to register the individual AST validators. Add the literal
validators for timestamp, duration and regular expressions through
`.addAstValidators` builder method:

```java
private static final CelValidator CEL_VALIDATOR =
    CelValidatorFactory.standardCelValidatorBuilder(CEL_COMPILER, CEL_RUNTIME)
        .addAstValidators(
            TimestampLiteralValidator.INSTANCE,
            DurationLiteralValidator.INSTANCE,
            RegexLiteralValidator.INSTANCE)
        .build();
```

Re-run the test. You should observe that the first three literal validation
tests pass.

There is one more test remaining to be fixed:

```java
@Test
public void validate_listHasMixedLiterals_throws() throws Exception {
  CelAbstractSyntaxTree ast = exercise8.compile("3 in [1, 2, '3']");

  // Note that `CelValidationResult` is the same result class used for the compilation path. This
  // means you could alternatively invoke `.getAst()` and handle `CelValidationException` as
  // usual.
  CelValidationResult validationResult = exercise8.validate(ast);

  CelValidationException e = assertThrows(CelValidationException.class, validationResult::getAst);
  assertThat(e)
      .hasMessageThat()
      .contains(
          "ERROR: <input>:1:13: expected type 'int' but found 'string'\n"
              + " | 3 in [1, 2, '3']\n"
              + " | ............^");
}
```

CEL offers a validator to catch literals with mixed types in lists or maps.
For example, `3 in [1, 2, "3"]` is a perfectly valid expression in CEL but
likely unintended as this would evaluate to false.

Add `HomogeneousLiteralValidator.newInstance()` then rerun the tests to confirm
that all tests pass:

```java
// Just like the compiler and runtime, the validator and optimizer can be statically
// initialized as their instances are immutable.
private static final CelValidator CEL_VALIDATOR =
    CelValidatorFactory.standardCelValidatorBuilder(CEL_COMPILER, CEL_RUNTIME)
        .addAstValidators(
            TimestampLiteralValidator.INSTANCE,
            DurationLiteralValidator.INSTANCE,
            RegexLiteralValidator.INSTANCE,
            HomogeneousLiteralValidator.newInstance())
        .build();
```

### Optimizers

Human authored expressions often contain redundancies that may cause suboptimal
evaluation. In such cases, optimization is highly beneficial if the ASTs will be
repeatedly evaluated. Conversely, there is little point in optimizing an AST if
it will be evaluated once.

We first look at a classic compiler optimization known as constant folding. Have
a look at the relevant test:

```java
@Test
public void optimize_constantFold_success() throws Exception {
  CelUnparser celUnparser = CelUnparserFactory.newUnparser();
  CelAbstractSyntaxTree ast = exercise8.compile("(1 + 2 + 3 == x) && (x in [1, 2, x])");

  CelAbstractSyntaxTree optimizedAst = exercise8.optimize(ast);

  assertThat(celUnparser.unparse(optimizedAst)).isEqualTo("6 == x");
}
```

> [!NOTE]
> Note: Unparser can be used to convert an AST into its textual representation.
In this exercise, it's used to verify the result of an AST optimization.

Constant folding will take all arithmetic expression containing only constant
values, computes the expression then replaces with the result. It will also
prune any branches of the expression that can be removed without affecting the
correctness (akin to dead-code elimination).

Run the optimizer tests (note the `--test_filter` flag):

```sh
bazel test --test_output=errors --test_filter=optimize //codelab/src/test/codelab:Exercise8Test
```

You should see 3 tests failing:

```
There were 3 failures:
1) optimize_commonSubexpressionElimination_success(codelab.Exercise8Test)
... and more
FAILURES!!!
Tests run: 3,  Failures: 3
```

Similar to how the validator was setup, the optimizer requires both the compiler
and runtime instances. Copy and paste the following into `Exercise8.java`:

```java
private static final CelOptimizer CEL_OPTIMIZER =
    CelOptimizerFactory.standardCelOptimizerBuilder(CEL_COMPILER, CEL_RUNTIME)
        .build();
```

Then change the code in `optimize` method as:

```java
/**
 * Optimizes a type-checked AST.
 *
 * @throws CelOptimizationException If the optimization fails.
 */
CelAbstractSyntaxTree optimize(CelAbstractSyntaxTree checkedAst) throws CelOptimizationException {
  return CEL_OPTIMIZER.optimize(checkedAst);
}
```

Next, register `ConstantFoldingOptimizer` via `.addAstOptimizers`:

```java
private static final CelOptimizer CEL_OPTIMIZER =
    CelOptimizerFactory.standardCelOptimizerBuilder(CEL_COMPILER, CEL_RUNTIME)
        .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
        .build();
```

Re-run the test. You should see 2 out of 3 tests passing now.

Please note that optimization removes parsing metadata from the AST, as
modifications may cause it to deviate from the original expression.
Practically, this means the error message will no longer indicate the source
location as shown in the test below:

```java
@Test
public void optimize_constantFold_evaluateError() throws Exception {
  CelAbstractSyntaxTree ast =
      exercise8.compile("request.headers.referer == 'https://' + 'cel.dev'");
  CelAbstractSyntaxTree optimizedAst = exercise8.optimize(ast);
  ImmutableMap<String, AttributeContext.Request> runtimeParameters =
      ImmutableMap.of("request", AttributeContext.Request.getDefaultInstance());

  CelEvaluationException e1 =
      assertThrows(CelEvaluationException.class, () -> exercise8.eval(ast, runtimeParameters));
  CelEvaluationException e2 =
      assertThrows(
          CelEvaluationException.class, () -> exercise8.eval(optimizedAst, runtimeParameters));
  // Note that the errors below differ by their source position.
  assertThat(e1)
      .hasMessageThat()
      .contains("evaluation error at <input>:15: key 'referer' is not present in map.");
  assertThat(e2)
      .hasMessageThat()
      .contains("evaluation error at <input>:0: key 'referer' is not present in map.");
}
```

The second optimization technique to cover is Common Subexpression Elimination
(CSE). It will replace instances of identical expressions to a single variable
holding the computed value.

Have a look at the following test and note the expression containing duplicate field
selections `request.auth.claims.group`:

```java
@Test
public void optimize_commonSubexpressionElimination_success() throws Exception {
  CelUnparser celUnparser = CelUnparserFactory.newUnparser();
  CelAbstractSyntaxTree ast =
      exercise8.compile(
          "request.auth.claims.group == 'admin' || request.auth.claims.group == 'user'");

  CelAbstractSyntaxTree optimizedAst = exercise8.optimize(ast);

  assertThat(celUnparser.unparse(optimizedAst))
      .isEqualTo(
          "cel.@block([request.auth.claims.group], @index0 == \"admin\" || @index0 == \"user\")");
}
```

CSE optimizes the expression by rewriting it to use a specialized internal
function `cel.@block`. This function takes a list of duplicate subexpressions
as its first argument, and a semantically equivalent rewritten expression as
its second. The subexpressions are lazily evaluated and memoized when accessed
by index (e.g., `@index0`).

Make the following changes in `Exercise8.java`:

```java
private static final CelOptimizer CEL_OPTIMIZER =
    CelOptimizerFactory.standardCelOptimizerBuilder(CEL_COMPILER, CEL_RUNTIME)
        .addAstOptimizers(
            ConstantFoldingOptimizer.getInstance(),
            SubexpressionOptimizer.getInstance())
        .build();
```

Re-run the tests to confirm that they pass.

## Custom AST Validation

As seen in the earlier exercise, CEL offers many built-in validators. There
are however situations where authoring a custom AST validator is beneficial to
improve user experience by providing context-specific feedback.

We'll be writing a validator to ensure that `AttributeContext.Request` message
is well formatted. Have a look at the test case:

```java
@Test
public void validate_invalidHttpMethod_returnsError() throws Exception {
  String expression =
      "google.rpc.context.AttributeContext.Request { \n"
          + "scheme: 'http', "
          + "method: 'GETTT', " // method is misspelled.
          + "host: 'cel.dev' \n"
          + "}";
  CelAbstractSyntaxTree ast = exercise9.compile(expression);

  CelValidationResult validationResult = exercise9.validate(ast);

  assertThat(validationResult.hasError()).isTrue();
  assertThat(validationResult.getErrorString())
      .isEqualTo(
          "ERROR: <input>:2:25: GETTT is not an allowed HTTP method.\n"
              + " | scheme: 'http', method: 'GETTT', host: 'cel.dev' \n"
              + " | ........................^");
  assertThrows(CelValidationException.class, validationResult::getAst);
}
```

Run the test:

```sh
bazel test --test_output=errors //codelab/src/test/codelab:Exercise9Test
```

You should see all three tests failing:

```
There were 3 failures:
1) validate_invalidHttpMethod_returnsError(codelab.Exercise9Test)
... and more
FAILURES!!!
Tests run: 3,  Failures: 3
```

The first step in writing a custom AST validator is to have a class implement
the `CelAstValidator` interface. In `Exercise9.java`, make the
following changes:

```java
static final class AttributeContextRequestValidator implements CelAstValidator {
    @Override
    public void validate(CelNavigableAst navigableAst, Cel cel, IssuesFactory issuesFactory) {
         // Implement validate method here
    }
}
```

We need a way to fetch then inspect the expression nodes of interest. For this,
CEL provides fluent APIs to navigate a compiled AST via navigable expressions.
A `CelNavigableExpr` allows you to traverse through its descendants or parent
with ease.

Let's write some logic to filter for expressions containing
`google.rpc.context.AttributeContext.Request` message name. Copy and paste the
following:

```java
@Override
public void validate(CelNavigableAst navigableAst, Cel cel, IssuesFactory issuesFactory) {
  navigableAst
      .getRoot()
      .allNodes()
      .filter(node -> node.getKind().equals(Kind.STRUCT))
      .map(node -> node.expr().struct())
      .filter(
          struct -> struct.messageName().equals("google.rpc.context.AttributeContext.Request"))
}
```

> [!TIP]
> Call `.toString()` on a `CelExpr` object to obtain a human-readable format
of the AST.

Next, we'll iterate through the fields of the message to confirm that it has
the correct HTTP method. Otherwise, we'll add it as an error through
`IssuesFactory`. Copy and paste the rest of the code:

```java
static final class AttributeContextRequestValidator implements CelAstValidator {
  private static final ImmutableSet<String> ALLOWED_HTTP_METHODS =
      ImmutableSet.of("GET", "POST", "PUT", "DELETE");

  @Override
  public void validate(CelNavigableAst navigableAst, Cel cel, IssuesFactory issuesFactory) {
    navigableAst
        .getRoot()
        .allNodes()
        .filter(node -> node.getKind().equals(Kind.STRUCT))
        .map(node -> node.expr().struct())
        .filter(
            struct -> struct.messageName().equals("google.rpc.context.AttributeContext.Request"))
        .forEach(
            struct -> {
              for (CelStruct.Entry entry : struct.entries()) {
                String fieldKey = entry.fieldKey();
                if (fieldKey.equals("method")) {
                  String entryStringValue = getStringValue(entry.value());
                  if (!ALLOWED_HTTP_METHODS.contains(entryStringValue)) {
                    issuesFactory.addError(
                        entry.value().id(), entryStringValue + " is not an allowed HTTP method.");
                  }
                }
              }
            });
  }

  /**
   * Reads the underlying string value from the expression.
   *
   * @throws UnsupportedOperationException if the expression is not a constant string value.
   */
  private static String getStringValue(CelExpr celExpr) {
    return celExpr.constant().stringValue();
  }
}
```

Register the custom validator via `.addAstValidators`:

```java
private static final CelValidator CEL_VALIDATOR =
    CelValidatorFactory.standardCelValidatorBuilder(CEL_COMPILER, CEL_RUNTIME)
        .addAstValidators(new AttributeContextRequestValidator())
        .build();
```

Run the test again and confirm that the first test case passes.

Let's look at the next test:

```java
@Test
public void validate_schemeIsHttp_returnsWarning() throws Exception {
  String expression =
      "google.rpc.context.AttributeContext.Request { \n"
          + "scheme: 'http', " // https is preferred but not required.
          + "method: 'GET', "
          + "host: 'cel.dev' \n"
          + "}";
  CelAbstractSyntaxTree ast = exercise9.compile(expression);

  CelValidationResult validationResult = exercise9.validate(ast);

  assertThat(validationResult.hasError()).isFalse();
  assertThat(validationResult.getIssueString())
      .isEqualTo(
          "WARNING: <input>:2:9: Prefer using https for safety.\n"
              + " | scheme: 'http', method: 'GET', host: 'cel.dev' \n"
              + " | ........^");
  // Because the validation result does not contain any errors, you can still evaluate it.
  assertThat(exercise9.eval(validationResult.getAst()))
      .isEqualTo(
          AttributeContext.Request.newBuilder()
              .setScheme("http")
              .setMethod("GET")
              .setHost("cel.dev")
              .build());
}
```

A validator does not necessarily have to produce a pass/fail outcome. It can
instead provide informational feedbacks or warnings. This is useful when an
expression has no correctness issue, but can be improved based on the
application context. Linting is a good example of this.

Make the following changes to produce a warning when `https` is not used as the
scheme:

```java
@Override
public void validate(CelNavigableAst navigableAst, Cel cel, IssuesFactory issuesFactory) {
  navigableAst
      .getRoot()
      .allNodes()
      .filter(node -> node.getKind().equals(Kind.STRUCT))
      .map(node -> node.expr().struct())
      .filter(
          struct -> struct.messageName().equals("google.rpc.context.AttributeContext.Request"))
      .forEach(
          struct -> {
            for (CelStruct.Entry entry : struct.entries()) {
              String fieldKey = entry.fieldKey();
              if (fieldKey.equals("method")) {
                String entryStringValue = getStringValue(entry.value());
                if (!ALLOWED_HTTP_METHODS.contains(entryStringValue)) {
                  issuesFactory.addError(
                      entry.value().id(), entryStringValue + " is not an allowed HTTP method.");
                }
              } else if (fieldKey.equals("scheme")) {
                String entryStringValue = getStringValue(entry.value());
                if (!entryStringValue.equals("https")) {
                  issuesFactory.addWarning(
                      entry.value().id(), "Prefer using https for safety.");
                }
              }
            }
          });
}
```

Re-run to confirm that the first two tests pass.

Another common need is to restrict the use of an expensive function call in an
unsafe manner. Suppose we have a function that issues an RPC. We'll write a
validator to ensure that it can't be used within a macro to prevent repeated
invocations within an expression.

Inspect the test case:

```java
@Test
public void validate_isPrimeNumberWithinMacro_returnsError() throws Exception {
  String expression = "[2,3,5].all(x, is_prime_number(x))";
  CelAbstractSyntaxTree ast = exercise9.compile(expression);

  CelValidationResult validationResult = exercise9.validate(ast);

  assertThat(validationResult.hasError()).isTrue();
  assertThat(validationResult.getErrorString())
      .isEqualTo(
          "ERROR: <input>:1:12: is_prime_number function cannot be used within CEL macros.\n"
              + " | [2,3,5].all(x, is_prime_number(x))\n"
              + " | ...........^");
}
```

Then copy and paste the following into `ComprehensionSafetyValidator` in `Exercise9.java`:

```java
/** Prevents nesting an expensive function call within a macro. */
static final class ComprehensionSafetyValidator implements CelAstValidator {
  private static final String EXPENSIVE_FUNCTION_NAME = "is_prime_number";

  @Override
  public void validate(CelNavigableAst navigableAst, Cel cel, IssuesFactory issuesFactory) {
    navigableAst
        .getRoot()
        .allNodes()
        .filter(node -> node.getKind().equals(Kind.COMPREHENSION))
        .forEach(
            comprehensionNode -> {
              boolean isFunctionWithinMacro =
                  comprehensionNode
                      .descendants()
                      .anyMatch(
                          node ->
                              node.expr()
                                  .callOrDefault()
                                  .function()
                                  .equals(EXPENSIVE_FUNCTION_NAME));
              if (isFunctionWithinMacro) {
                issuesFactory.addError(
                    comprehensionNode.id(),
                    EXPENSIVE_FUNCTION_NAME + " function cannot be used within CEL macros.");
              }
            });
  }
}
```

> [!NOTE]
> This doesn't stop the expression author from simply chaining the calls together outside the macro (e.g: `is_prime_number(2) && is_prime_number(3) ...)`. One could easily introduce a validator to catch these cases too if desired.

> [!TIP]
> Use `(kind)OrDefault` to consolidate checking for the expression kind and the retrieval of the underlying expression for brevity. `callOrDefault` here is an example.

Finally, register the custom validator:

```java
private static final CelValidator CEL_VALIDATOR =
    CelValidatorFactory.standardCelValidatorBuilder(CEL_COMPILER, CEL_RUNTIME)
        .addAstValidators(
            new AttributeContextRequestValidator(),
            new ComprehensionSafetyValidator())
        .build();
```

Re-run the test to confirm that all tests pass.

## Custom Types

### Exercise 10: Custom Types

Most users extend the type system by introducing new protobuf types to the CEL
environment. CEL has well-defined rules for mapping protobuf messages to struct
types so this is typically the simplest approach.

If you have a use case where the data type is not represented as a proto, there
are two type kinds that can be introduced:

- `struct`: an object with structured fields
- `opaque`: a type that is only meaningful as the input or output of an
  extension function.

In CEL-Java, custom non-proto types are represented as `OpaqueType` at compile
time and `OpaqueValue` at runtime. The recommended way to implement a custom
opaque type is **Direct Extension**, where an immutable domain class extends
`OpaqueValue`, returns `this` from `value()`, and returns its corresponding
`OpaqueType` from `celType()`.

Open `Exercise10.java`, where two custom opaque types (`net.Address` and
`net.AddressMatcher`), their `CelTypeProvider` (`NETWORK_TYPE_PROVIDER`), and
their `OpaqueValue` implementations (`Address` and `AddressMatcher`) are
defined.

Run the test:

```sh
blaze test --test_output=errors //third_party/java/cel/codelab/src/test/codelab:Exercise10Test
```

You should see the tests failing with compilation errors because `ip`, the
custom types, and the network functions (`net.parseAddress`,
`net.parseAddressMatcher`, and `containsAddress`) have not yet been declared on
the compiler or bound on the runtime.

First, update `compile` in `Exercise10.java` to register
`NETWORK_TYPE_PROVIDER`, declare `ip` and the type identifiers (`net.Address`
and `net.AddressMatcher`), and declare the extension functions:

```java
CelAbstractSyntaxTree compile(String expression) throws CelValidationException {
  CelCompiler celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setResultType(SimpleType.BOOL)
          .setTypeProvider(NETWORK_TYPE_PROVIDER)
          .addVar("ip", SimpleType.STRING)
          .addVar("net.Address", TypeType.create(ADDRESS_TYPE))
          .addVar("net.AddressMatcher", TypeType.create(ADDRESS_MATCHER_TYPE))
          .addFunctionDeclarations(
              newFunctionDeclaration(
                  "net.parseAddress",
                  newGlobalOverload(
                      "net_parseAddress_string", ADDRESS_TYPE, SimpleType.STRING)),
              newFunctionDeclaration(
                  "net.parseAddressMatcher",
                  newGlobalOverload(
                      "net_parseAddressMatcher_string",
                      ADDRESS_MATCHER_TYPE,
                      SimpleType.STRING)),
              newFunctionDeclaration(
                  "containsAddress",
                  newMemberOverload(
                      "net_AddressMatcher_containsAddress_net_Address",
                      SimpleType.BOOL,
                      ADDRESS_MATCHER_TYPE,
                      ADDRESS_TYPE)))
          .build();

  return celCompiler.compile(expression).getAst();
}
```

Next, update `eval` in `Exercise10.java` to register `NETWORK_TYPE_PROVIDER` and
bind the function implementations:

```java
Object eval(CelAbstractSyntaxTree ast, Map<String, ?> parameterValues)
    throws CelEvaluationException {
  CelRuntime celRuntime =
      CelRuntimeFactory.plannerRuntimeBuilder()
          .setTypeProvider(NETWORK_TYPE_PROVIDER)
          .addFunctionBindings(
              CelFunctionBinding.from(
                  "net_parseAddress_string", String.class, Address::parse),
              CelFunctionBinding.from(
                  "net_parseAddressMatcher_string", String.class, AddressMatcher::parse),
              CelFunctionBinding.from(
                  "net_AddressMatcher_containsAddress_net_Address",
                  AddressMatcher.class,
                  Address.class,
                  AddressMatcher::containsAddress))
          .build();

  return celRuntime.createProgram(ast).eval(parameterValues);
}
```

Re-run the unit tests to confirm that all test cases pass.
