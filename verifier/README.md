# CEL Java Verifier

The **CEL Java Verifier** is a formal verification framework for CEL Java,
designed to statically prove semantic properties of CEL expressions and
policies.

Powered by the [Z3 SMT solver](https://github.com/Z3Prover/z3), the verifier
translates CEL Abstract Syntax Trees (ASTs) into mathematical formulas to prove
equivalence, satisfiability, and validity without executing the expressions.

---

## Overview

CEL is side-effect free with guaranteed termination, but as expressions grow in
complexity, ensuring correctness under all possible inputs becomes challenging.
The CEL Verifier addresses this by allowing you to mathematically prove
properties about your expressions.

### Common Use Cases

*   **Compliance Auditing:** Statically prove that a critical resource is
    mathematically protected (e.g., "prove that access is never allowed unless
    `request.auth.claims.role == 'admin'`").
*   **Optimization & Safe Refactoring:** Verify that a simplified or optimized
    version of an expression behaves identically to the original version for
    all possible inputs.
*   **Dead Code Detection:** Identify branches in an expression that can never
    be reached (are unsatisfiable).

---

## Key Features

*   **Satisfiability & Validity Proving:** Check if an expression can ever
    evaluate to `true` (satisfiability) or if it is guaranteed to always be
    `true` (validity). When checking satisfiability (`isSatisfiable`), the
    verifier produces a satisfying model (witness assignments) showing concrete
    inputs that make the expression true.
*   **Logical Equivalence:** Prove that two different ASTs or Policies are
    semantically identical.
*   **Bounded Model Checking (BMC):** Safely verify list and map comprehensions
    (`all`, `exists`, `map`, `filter`) by statically unrolling them up to a
    configurable limit.
*   **Counterexample & Witness Generation:** When validity (`isAlwaysTrue`) or
    equivalence verification fails, the verifier generates a human-readable
    counterexample showing the inputs that caused the violation. When checking
    satisfiability (`isSatisfiable`), it generates concrete variable assignments
    (satisfying model / witness) showing the inputs that satisfy the condition.
*   **Partial Evaluation (Unknowns) Support:** Define variables that are
    permitted to evaluate to `Unknown` during verification, mirroring CEL's
    runtime partial evaluation.
*   **Custom Invariants Verification:** Allows policy authors to define safety
    invariants (e.g., "port must always be secure if external access is
    allowed") and mathematically prove that the policy never violates them
    across all possible input states.

```java
CelVerifier verifier = CelVerifierFactory.newVerifier()
    .addUnknownIdentifier("request.headers") // Exclude dynamic fields from failure paths
    .build();
```

---

## Upcoming Capabilities

The following features are planned for future releases:

*   **Deep Reachability Analysis:** Statically analyzes nested policy rules
    to detect unreachable execution paths (dead code) that can never be
    executed under any input.
*   **Rule Shadowing & Independence Detection:** Detects when sibling rules
    in a policy conflict or shadow each other (i.e., a rule is partially or
    fully shadowed by a preceding rule with overlapping conditions), ensuring
    deterministic and intended policy routing.

---

## Usage

### 1. AST Equivalence Verification

The following example demonstrates how to verify if two CEL expressions
are logically equivalent.

```java
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.verifier.CelVerificationException;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.CelVerifier;
import dev.cel.verifier.CelVerifierFactory;
import java.time.Duration;

public class VerifierExample {
  public static void main(String[] args) throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder()
        .addVar("x", SimpleType.INT)
        .build();

    // Compile two logically equivalent expressions
    CelAbstractSyntaxTree astA = compiler.compile("x > 10").getAst();
    CelAbstractSyntaxTree astB = compiler.compile("10 < x").getAst();

    // Create and configure the verifier
    CelVerifier verifier = CelVerifierFactory.newVerifier()
        .setTimeout(Duration.ofSeconds(2))
        .build();

    CelVerificationResult result;
    try {
      // Verify equivalence
      result = verifier.verifyEquivalence(astA, astB);
    } catch (CelVerificationException e) {
      System.out.println("Verification failed or timed out: " + e.getMessage());
      return;
    }

    switch (result.status()) {
      case VERIFIED:
        System.out.println("Expressions are logically equivalent!");
        break;
      case VIOLATED:
        System.out.println(result.message());
        // Example output if expressions were NOT equivalent:
        // Equivalence violation detected. Counterexample input:
        //   x = ...
        break;
      case INCONCLUSIVE:
        // INCONCLUSIVE means the solver could not positively confirm VERIFIED or VIOLATED
        // due to things like loop truncation (BMC) or uninterpreted functions.
        System.out.println("Verification was inconclusive: " + result.message());
        break;
    }
  }
}
```

### 2. Policy Equivalence Verification

You can also verify the equivalence of two structured CEL Policies.

```java
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.types.SimpleType;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicyCompiler;
import dev.cel.policy.CelPolicyCompilerFactory;
import dev.cel.policy.CelPolicyParser;
import dev.cel.policy.CelPolicyParserFactory;
import dev.cel.verifier.CelPolicyVerifier;
import dev.cel.verifier.CelPolicyVerifierFactory;
import dev.cel.verifier.CelVerificationException;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.CelVerifier;
import dev.cel.verifier.CelVerifierFactory;

public class PolicyVerifierExample {
  private static final Cel CEL = CelFactory.plannerCelBuilder()
      .addVar("role", SimpleType.STRING)
      .addVar("country", SimpleType.STRING)
      .addVar("port", SimpleType.INT)
      .build();

  private static final CelPolicyParser PARSER = CelPolicyParserFactory.newYamlParserBuilder()
      .enableSimpleVariables(true)
      .build();

  private static final CelPolicyCompiler POLICY_COMPILER =
      CelPolicyCompilerFactory.newPolicyCompiler(CEL).build();

  // Verifiers are immutable and thread-safe, making them safe to store as static constants
  private static final CelVerifier AST_VERIFIER = CelVerifierFactory.newVerifier().build();

  private static final CelPolicyVerifier POLICY_VERIFIER =
      CelPolicyVerifierFactory.newVerifier(POLICY_COMPILER, AST_VERIFIER).build();

  public static void main(String[] args) throws Exception {
    // Define legacy policy (single complex expression)
    String yamlLegacy = """
        name: legacy_authz
        rule:
          match:
            - output: '(role == "admin" || (role == "editor" && country == "US")) && port == 443'
        """;

    // Define refactored policy with a bug (missing country check)
    String yamlRefactored = """
        name: refactored_authz
        rule:
          variables:
            - is_admin: 'role == "admin"'
            - is_editor: 'role == "editor"' # Bug: Missing country == 'US' check!
            - is_secure: 'port == 443'
          match:
            - output: '(variables.is_admin || variables.is_editor) && variables.is_secure'
        """;

    // Parse both policies
    CelPolicy policyLegacy = PARSER.parse(yamlLegacy);
    CelPolicy policyRefactored = PARSER.parse(yamlRefactored);

    CelVerificationResult result;
    try {
      // Verify equivalence
      result = POLICY_VERIFIER.verifyEquivalence(policyLegacy, policyRefactored);
    } catch (CelVerificationException e) {
      System.out.println("Verification failed or timed out: " + e.getMessage());
      return;
    }

    switch (result.status()) {
      case VERIFIED:
        System.out.println("Policies are equivalent!");
        break;
      case VIOLATED:
        System.out.println("Refactoring bug detected!");
        System.out.println(result.message());
        // Output:
        // Equivalence violation detected. Counterexample input:
        //   country = "a"
        //   role = "editor"
        //   port = 443
        break;
      case INCONCLUSIVE:
        System.out.println("Verification was inconclusive: " + result.message());
        break;
    }
  }
}
```

### 3. Satisfiability Checking & Witness Generation

When checking whether an expression is satisfiable using `isSatisfiable`, the
verifier returns `VERIFIED` if there exists at least one input combination
where the expression evaluates to `true`. Furthermore, `result.message()`
provides concrete satisfying model assignments (witness/test case generation).

```java
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.CelVerifier;
import dev.cel.verifier.CelVerifierFactory;

public class SatisfiabilityExample {
  public static void main(String[] args) throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder()
        .addVar("role", SimpleType.STRING)
        .addVar("port", SimpleType.INT)
        .build();

    // Check if an authorization condition can ever be met
    CelAbstractSyntaxTree ast =
        compiler.compile("role == 'editor' && port > 1024 && port < 65535").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    switch (result.status()) {
      case VERIFIED:
        System.out.println("Condition is satisfiable!");
        System.out.println(result.message());
        // Output:
        // Condition is satisfiable. Satisfying input:
        //   port = 1025
        //   role = "editor"
        break;
      case VIOLATED:
        System.out.println("Condition is completely unsatisfiable.");
        break;
      case INCONCLUSIVE:
        System.out.println("Verification was inconclusive: " + result.message());
        break;
    }
  }
}
```

### 4. Policy Invariants Verification

You can verify that custom invariants (`assume` preconditions and `assert`
clauses) declared on a `CelPolicy` hold mathematically across all possible
input states. The reserved `rule.result` identifier matches the return
value of the policy.

```java
import com.google.common.collect.ImmutableMap;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.types.SimpleType;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicyCompiler;
import dev.cel.policy.CelPolicyCompilerFactory;
import dev.cel.policy.CelPolicyParser;
import dev.cel.policy.CelPolicyParserFactory;
import dev.cel.verifier.CelPolicyVerifier;
import dev.cel.verifier.CelPolicyVerifierFactory;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.CelVerifier;
import dev.cel.verifier.CelVerifierFactory;

public class InvariantsExample {
  private static final Cel CEL = CelFactory.plannerCelBuilder()
      .addVar("port", SimpleType.INT)
      .build();

  private static final CelPolicyParser PARSER = CelPolicyParserFactory.newYamlParserBuilder().build();

  private static final CelPolicyCompiler POLICY_COMPILER =
      CelPolicyCompilerFactory.newPolicyCompiler(CEL).build();

  private static final CelVerifier AST_VERIFIER = CelVerifierFactory.newVerifier().build();

  private static final CelPolicyVerifier POLICY_VERIFIER =
      CelPolicyVerifierFactory.newVerifier(POLICY_COMPILER, AST_VERIFIER).build();

  public static void main(String[] args) throws Exception {
    String yamlPolicy = """
        name: secure_access_policy
        rule:
          match:
            - condition: port == 80
              output: 'true'
            - output: 'false'
        verification:
          invariants:
            - id: always_secure
              assert:
                - rule.result == false
        """;

    CelPolicy policy = PARSER.parse(yamlPolicy);
    ImmutableMap<String, CelVerificationResult> results = POLICY_VERIFIER.verifyInvariants(policy);

    CelVerificationResult result = results.get("always_secure");
    switch (result.status()) {
      case VERIFIED:
        System.out.println("Invariant proven!");
        break;
      case VIOLATED:
        System.out.println("Invariant violated!");
        System.out.println(result.message());
        // Output:
        // Implication violation detected. Counterexample input:
        //   port = 80
        break;
      case INCONCLUSIVE:
        System.out.println("Verification was inconclusive: " + result.message());
        break;
    }
  }
}
```

---

## Limitations & Best Practices

### Limitations

*   **Unsupported Standard Functions (Uninterpreted Functions):** Not all
    CEL standard library functions have SMT axioms defined yet. Unsupported
    functions are treated as *uninterpreted functions* by Z3 (the solver
    only guarantees that identical inputs yield identical outputs, but does
    not understand the function's internal logic or return types).
    Consequently, verifications that rely on the specific semantics of
    these functions may return `VerificationStatus.INCONCLUSIVE`. Support for
    more standard library functions will be added incrementally.

### Best Practices & Performance

*   **Prefer Strong Typing over `dyn`:** Always declare variables with
    specific concrete types (e.g., `int`, `string`, `bool`, or specific
    protobuf message types). Omitting the type or using `dyn` forces the
    verifier to perform expensive runtime type checks symbolically across
    multiple theories, which significantly slows down verification. Using
    concrete types allows Z3 to use specialized solvers directly for much
    faster results.
*   **Avoid Floating Point Numbers:** Using floating point numbers in your
    CEL expressions can significantly increase the time it takes for the
    verifier to produce a result. It is recommended to use integers for all
    counting and comparison logic unless floating point precision is
    explicitly required.

---

## Configuration & Tuning

### Timeouts

SMT solving is NP-complete and can theoretically stop responding or take an
exponential amount of time for complex formulas.
The verifier uses a default timeout of 10 seconds. It is recommended to
configure this to a reasonable duration for your specific use case using
`setTimeout(Duration)`. Note that this is a soft timeout evaluated periodically
by the Z3 solver during its search phase.
If the solver times out, the verifier will throw a `CelVerificationException`
which should be explicitly caught and handled by the caller.

### Comprehensions and Bounded Model Checking

Because CEL lists/maps can be dynamically sized, the verifier cannot
statically evaluate loops of infinite or unknown size.
To handle comprehensions (`all`, `exists`, `map`, `filter`), the verifier uses
Bounded Model Checking (BMC) to statically unroll loops up to a limit
configured via `setComprehensionUnrollLimit(int)` (defaults to 5).

What this means for verification:

*   **Equivalence Checking:** Works seamlessly out-of-the-box for refactored
    policies containing matching comprehensions, as both sides are unrolled
    to the same limit.
*   **Inconclusive Verification:** Expressions with comprehensions over
    unconstrained dynamic lists will safely evaluate to `Unknown` for
    inputs exceeding the unroll limit. If the verifier cannot definitively
    prove or disprove a property for all possible list sizes (e.g., when
    checking `isAlwaysTrue` or `isSatisfiable`), it will return
    `VerificationStatus.INCONCLUSIVE`.
*   **Warning:** Setting the unroll limit too high will exponentially
    increase verification time and memory usage. Prefer keeping it near the
    default unless you have a specific need and bounded inputs.

---
