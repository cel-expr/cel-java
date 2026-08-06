# Common Expression Language for Java

The Common Expression Language (CEL) is a non-Turing complete language designed
for simplicity, speed, safety, and portability. CEL's C-like [syntax][1] looks
nearly identical to equivalent expressions in C++, Go, Java, and TypeScript.

```java
// Check whether a resource name starts with a group name.
resource.name.startsWith("/groups/"+auth.claims.group)
```

```go
// Determine whether the request is in the permitted time window.
request.time - resource.age < duration("24h")
```

```typescript
// Check whether all resource names in a list match a given filter.
auth.claims.email_verified && resources.all(r, r.startsWith(auth.claims.email))
```

A CEL "program" is a single expression. The examples have been tagged as
`java`, `go`, and `typescript` within the markdown to showcase the commonality
of the syntax.

CEL is ideal for lightweight expression evaluation when a fully sandboxed
scripting language is too resource intensive.

---

* [Quick Start](#quick-start)
* [Overview](#overview)
    * [Environment Setup](#environment-setup)
    * [Parsing](#parsing)
    * [Checking](#checking)
        * [Macros](#macros)
    * [Evaluation](#evaluation)
        * [Errors](#errors)
    * [Extensions](#extensions)
* [Install](#install)
* [Common Questions](#common-questions)
* [License](#license)

---

## Quick Start

### Install

CEL-Java is available in Maven Central Repository. [Download the JARs here][8] or add the following to your build dependencies:

**Maven (pom.xml)**:

```xml
<dependency>
  <groupId>dev.cel</groupId>
  <artifactId>cel</artifactId>
  <version>0.14.0</version>
</dependency>
```

**Gradle**

```gradle
implementation 'dev.cel:cel:0.14.0'
```

Then run this example:

```java
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.Map;

public class HelloWorld {
  // Construct the compilation and runtime environments.
  // These instances are immutable and thus trivially thread-safe and amenable to caching.
  private static final CelCompiler CEL_COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder().addVar("my_var", SimpleType.STRING).build();
  private static final CelRuntime CEL_RUNTIME =
      CelRuntimeFactory.plannerRuntimeBuilder().build();

  public void run() throws CelValidationException, CelEvaluationException {
    // Compile the expression into an Abstract Syntax Tree.
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("my_var + '!'").getAst();

    // Plan an executable program instance.
    CelRuntime.Program program = CEL_RUNTIME.createProgram(ast);

    // Evaluate the program with an input variable.
    String result = (String) program.eval(Map.of("my_var", "Hello World"));
    System.out.println(result); // 'Hello World!'
  }
}
```

## Overview

Determine the variables and functions you want to provide to CEL. Parse and
check an expression to make sure it's valid. Then evaluate the output AST
against some input. Checking is optional, but strongly encouraged.

### Environment Setup

Configuration for the entire CEL stack can be done all at once via the
`CelFactory.standardCelBuilder()`, or can be composed into compilation and
evaluation via the `CelCompilerFactory` and `CelRuntimeFactory`.

The simplest form of CEL usage is as follows:

```java
Cel cel = CelFactory.standardCelBuilder().build();
```

More commonly, your application will want to configure type-checking separately
from the runtime. Use `CelCompilerFactory` to construct a compilation
environment and declare the types, macros, variables, and functions to use with
your CEL application:

```java
// Example environment for the following expression:
//    resource.name.startsWith('/groups/' + group)
CelCompiler cel = CelCompilerFactory.standardCelCompilerBuilder()
    .setStandardMacros(CelStandardMacro.HAS)
    .setContainer("google.rpc.context.AttributeContext")
    .addMessageTypes(AttributeContext.getDescriptor())
    .addVar("resource",
        StructTypeReference.create("google.rpc.context.AttributeContext.Resource"))
    .addVar("group", SimpleType.STRING)
    .build();
```

More information about the features which are supported on the builder may be
found in the [`CelCompilerBuilder`][9].

### Parsing

Some CEL use cases only require parsing of an expression in order to be useful.
For example, one example might want to check whether the expression contains any
nested comprehensions, or possibly to pass the parsed expression to a C++ or Go
binary for evaluation.

```java
CelValidationResult parseResult =
    cel.parse("resource.name.startsWith('/groups/' + group)");
try {
  return parseResult.getAst();
} catch (CelValidationException e) {
  // Handle exception...
}
```

### Checking

Type-checking is performed on `CelAbstractSyntaxTree` values to ensure that the
expression is well formed and all variable and function references are defined.

Type-checking can be performed immediately after parsing an expression:

```java
try {
  CelValidationResult parseResult =
      cel.parse("resource.name.startsWith('/groups/' + group)");
  CelValidationResult checkResult = cel.check(parseResult.getAst());
  return checkResult.getAst();
} catch (CelValidationException e) {
  // Handle exception...
}
```

Or, the parse and type-check can be combined into the `compile` call. This is
likely the more common need.

```java
CelValidationResult compileResult =
    cel.compile("resource.name.startsWith('/groups/' + group)");
try {
  return compileResult.getAst();
} catch (CelValidationException e) {
  // Handle exception...
}
```

#### Macros

Macros were introduced to support optional CEL features that might not be
desired in all use cases without the syntactic burden and complexity such
features might desire if they were part of the core CEL syntax. Macros are
expanded at parse time and their expansions are type-checked at check time.

For example, when macros are enabled it is possible to support bounded iteration
/ fold operators. The macros `all`, `exists`, `exists_one`, `filter`, and `map`
are particularly useful for evaluating a single predicate against list and map
values.

```javascript
// Ensure all tweets are less than 140 chars
tweets.all(t, t.size() <= 140)
```

The `has` macro is useful for unifying field presence testing logic across
protobuf types and dynamic (JSON-like) types.

```javascript
// Test whether the field is a non-default value if proto-based, or defined
// in the JSON case.
has(message.field)
```

Both cases traditionally require special syntax at the language level, but these
features are exposed via macros in CEL.

Refer to the [CEL Specification][10] for full listings of available macros. To
leverage them, simply set the desired macros via `setStandardMacros` on the
builder:

```java
CelCompiler.standardCelBuilder()
  .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
```

### Evaluation

Expressions can be evaluated using once they are type-checked/compiled by
creating a `CelRuntime.Program` from a `CelAbstractSyntaxTree`:

```java
CelRuntime celRuntime = CelRuntimeFactory.plannerRuntimeBuilder().build();
try {
  CelRuntime.Program program = celRuntime.createProgram(compileResult.getAst());
  return program.eval(
      ImmutableMap.of(
          "resource", Resource.newBuilder().setName("/groups/").build(),
          "group", "admin"
      ));
} catch (CelEvaluationException e) {
  // Handle evaluation exceptions ...
}
```

The evaluation is thread-safe and side effect free thus many different inputs can
be sent to the same `cel.Program`.

#### Partial State

In distributed apps it is not uncommon to have edge caches and central services.
If possible, evaluation should happen at the edge, but it isn't always possible
to know the full state required for all values and functions present in the CEL
expression.

To improve the odds of successful evaluation with partial state, CEL uses
commutative logical operators `&&`, `||`. If an error or unknown value (not the
same thing) is encountered on the left-hand side, the right-hand side is
evaluated also to determine the outcome. While it is possible to implement
evaluation with partial state without this feature, this method was chosen
because it aligns with the semantics of SQL evaluation and because it's more
robust to evaluation against dynamic data types such as JSON inputs.

In the following truth-table, the symbols `<x>` and `<y>` represent error or
unknown values, with the `?` indicating that the branch is not taken due to
short-circuiting. When the result is `<x, y>` this means that both the args are
possibly relevant to the result.

| Expression          | Result   |
|---------------------|----------|
| `false && ?`        | `false`  |
| `true && false`     | `false`  |
| `<x> && false`      | `false`  |
| `true && true`      | `true`   |
| `true && <x>`       | `<x>`    |
| `<x> && true`       | `<x>`    |
| `<x> && <y>`        | `<x, y>` |
| `true \|\| ?`       | `true`   |
| `false \|\| true`   | `true`   |
| `<x> \|\| true`     | `true`   |
| `false \|\| false`  | `false`  |
| `false \|\| <x>`    | `<x>`    |
| `<x> \|\| false`    | `<x>`    |
| `<x> \|\| <y>`      | `<x, y>` |

### Errors

Parse and check errors have friendly error messages with pointers to where the
issues occur in source:

```sh
ERROR: <input>:1:40: undefined field 'undefined'
    | TestAllTypes{single_int32: 1, undefined: 2}
    | .......................................^`,
```

Both the parsed and checked expressions contain source position information
about each node that appears in the output AST. This information can be used
to determine error locations at evaluation time as well.

### Extensions

CEL-Java offers a suite of [canonical extensions][11] to support commonly
needed features that falls outside the CEL specification.

Examples:

```java
// String manipulation
'hello hello'.replace('he', 'we')     // returns 'wello wello'
'hello hello hello'.split(' ')     // returns ['hello', 'hello', 'hello']

// Math extensions
math.greatest(-42.0, -21.5, -100.0)   // -21.5
math.least(-42.0, -21.5, -100.0)   // -100.0

// Proto extensions
proto.getExt(msg, google.expr.proto2.test.int32_ext) // returns int value

// Local bindings
cel.bind(a, 'hello',
    cel.bind(b, 'world', a + b + b + a)) // "helloworldworldhello"
```

## Common Questions

### Why not JavaScript, Lua, or WASM?

JavaScript and Lua are rich languages that require sandboxing to execute safely.
Sandboxing is costly and factors into the "what will I let users evaluate?"
question heavily when the answer is anything more than O(n)
complexity.

CEL evaluates linearly with respect to the size of the expression and the input
being evaluated when macros are disabled. The only functions beyond the
built-ins that may be invoked are provided by the host environment. While
extension functions may be more complex, this is a choice by the application
embedding CEL.

But, why not WASM? WASM is an excellent choice for certain applications and is
far superior to embedded JavaScript and Lua, but it does not have support for
garbage collection and non-primitive object types require semi-expensive calls
across modules. In most cases CEL will be faster and just as portable for its
intended use case, though for node.js and web-based execution CEL too may offer
a WASM evaluator with direct to WASM compilation.

### Do I need to Parse _and_ Check?

Checking is an optional, but strongly suggested, step in CEL expression
validation. It is sufficient in some cases to simply Parse and rely on the
runtime bindings and error handling to do the right thing.

### Where can I learn more about the language?

* See the [CEL Spec][1] for the specification and conformance test suite.
* Ask for support on the [CEL Java Discuss][2] Google group.

### How can I contribute?

* See [CONTRIBUTING.md](./CONTRIBUTING.md) to get started.
* Use [GitHub Issues][7] to request features or report bugs.

### Dependencies

Java 8 or newer is required.

| Library               | Details              |
|-----------------------|----------------------|
| [Guava][3]            | N/A                  |
| [RE2/J][4]            | N/A                  |
| [Protocol Buffers][5] | Full or lite runtime |
| [ANTLR4][6]           | Java runtime         |

## License

Released under the [Apache License](LICENSE).

[1]:  https://github.com/cel-expr/cel-spec
[2]:  https://groups.google.com/forum/#!forum/cel-java-discuss
[3]:  https://github.com/google/guava
[4]:  https://github.com/google/re2j
[5]:  https://github.com/protocolbuffers/protobuf/tree/master/java
[6]:  https://github.com/antlr/antlr4/tree/master/runtime/Java
[7]:  https://github.com/cel-expr/cel-java/issues
[8]:  https://search.maven.org/search?q=g:dev.cel
[9]:  https://github.com/cel-expr/cel-java/blob/main/compiler/src/main/java/dev/cel/compiler/CelCompilerBuilder.java
[10]:  https://github.com/cel-expr/cel-spec/blob/master/doc/langdef.md#macros
[11]:  https://github.com/cel-expr/cel-java/blob/main/extensions/src/main/java/dev/cel/extensions/README.md
