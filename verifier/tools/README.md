# CEL Java Verifier CLI & Interactive REPL Tool

The CEL Java Verifier comes with a command-line tool (`cel-verifier`) and an
interactive REPL shell for testing satisfiability, validity, equivalence,
and policy invariants without writing Java code.

## Running the CLI Tool

### Standalone Executable (Prebuilt JAR)

You can download the standalone executable fat-JAR (`dev.cel:verifier-cli`)
directly from Maven Central and invoke it with `java -jar`:

<!-- disableFinding(LINE_OVER_80) -->
```bash
# Download the latest CLI JAR (Note: this is an uber-JAR)
curl -LO https://repo1.maven.org/maven2/dev/cel/verifier-cli/0.14.0/verifier-cli-0.14.0.jar

# Launch interactive REPL shell
java -jar verifier-cli-0.14.0.jar repl

# Run a one-shot verification command
java -jar verifier-cli-0.14.0.jar check-sat \
  --expr "role == 'editor' && port > 1024" \
  --var "role:string" \
  --var "port:int"

# Run with JSON output format for CI/CD integrations
java -jar verifier-cli-0.14.0.jar check-sat \
  --expr "role == 'editor'" \
  --var "role:string" \
  --output_format=json

# Display help and available commands
java -jar verifier-cli-0.14.0.jar --help
```

### Running via Bazel

```bash
# Launch interactive REPL shell
bazel run //verifier/tools:cel_verifier_tool -- repl

# Run a one-shot verification command
bazel run //verifier/tools:cel_verifier_tool -- \
  check-sat \
  --expr "role == 'editor' && port > 1024" \
  --var "role:string" \
  --var "port:int"

# Run with JSON output format for CI/CD integrations
bazel run //verifier/tools:cel_verifier_tool -- \
  check-sat \
  --expr "role == 'editor'" \
  --var "role:string" \
  --output_format=json
```

## CLI Commands

*   `check-sat --expr "..."`: Verifies satisfiability of an expression and
    prints witness inputs if satisfiable.
*   `check-valid --expr "..."`: Proves validity (`isAlwaysTrue`) and prints
    a counterexample if invalid.
*   `verify-equiv --expr1 "..." --expr2 "..."`: Proves logical equivalence
    between two CEL expressions.
*   `verify-policy --file policy.yaml`: Verifies policy invariants defined
    in a YAML policy file.
*   `repl`: Enters interactive verification shell mode.

## Command Options

The verification commands (`check-sat`, `check-valid`, `verify-equiv`,
`verify-policy`) accept the following options:

### Variable Declarations (`--var`, `-v`)

Declare variables in `name:type` format. Multiple variables can be declared by
repeating the `--var` option.

Supported types:

*   Primitive types: `int`, `uint`, `string`, `bool`, `double`, `bytes`, `dyn`
*   Well-known types: `timestamp`, `duration`
*   List types: `list<T>` (e.g., `--var "tags:list<string>"`)
*   Map types: `map<K, V>` (e.g., `--var "scores:map<string, int>"`)
*   Optional types: `optional<T>` (e.g., `--var "opt_flag:optional<bool>"`)
*   Protobuf types: Coming soon

Examples:
```bash
--var "role:string" --var "port:int" --var "tags:list<string>" --var "created_at:timestamp" --var "opt_flag:optional<bool>"
```

### Unknown Identifiers (`--unknown`, `-u`)

Permit specific identifiers or attributes (e.g., `request.headers`) to
evaluate to `Unknown` during verification:

```bash
--unknown "request.headers" --unknown "auth.credentials"
```

### Solver Timeout (`--timeout`)

Set maximum Z3 SMT solver timeout in seconds (default: `10`):

```bash
--timeout 15
```

### Comprehension Unroll Limit (`--unroll-limit`)

Set bounded unroll limit for comprehensions and loop macros like `.all()` and
`.exists()` (default: `5`):

```bash
--unroll-limit 10
```

### Output Format (`--output_format`, `-fmt`)

Set CLI output format (`TEXT` or `JSON`, default: `TEXT`):

```bash
--output_format json
```

## Exit Codes

*   `0`: Verification succeeded / condition verified.
*   `1`: Violation or counterexample found.
*   `2`: Inconclusive result (solver unknown or timeout).
*   `3`: Error (syntax compilation error, missing file, or execution error).

## Interactive REPL Shell

The REPL shell provides an interactive, stateful environment to execute CEL
formal verification queries without re-declaring variables or re-running CLI
parameters for every query.

### Launching the REPL

```bash
bazel run //verifier/tools:cel_verifier_tool -- repl
```

### REPL Commands

| Command | Description | Example |
|---|---|---|
| `:var <name> <type>` | Declare a variable in session state | `:var role string` |
| `:unknown <id>` | Mark identifier as Unknown | `:unknown request.headers` |
| `:timeout <sec>` | Set Z3 solver timeout in seconds (default: 10s) | `:timeout 5` |
| `:unroll <limit>` | Set comprehension unroll limit (default: 5) | `:unroll 3` |
| `:vars` | Display declared session variables & config | `:vars` |
| `:clear` | Reset session state (clears variables & unknowns) | `:clear` |
| `:help [cmd]` | Display built-in help or command details | `:help var` |
| `:quit` / `:exit` | Exit the interactive REPL shell | `:quit` |

### Verification Queries in REPL

*   **Satisfiability (`sat <expr>` or `<expr>`):** Checks if the expression
    can evaluate to `true` for any assignment of session variables. Outputs
    satisfying witness inputs if satisfiable.
*   **Validity (`valid <expr>`):** Proves whether the expression evaluates
    to `true` for ALL possible variable assignments. Outputs a counterexample
    if invalid.
*   **Equivalence (`equiv <expr1> <=> <expr2>`):** Proves whether two
    expressions are logically identical across all inputs. Outputs a
    counterexample if not equivalent.

### Example REPL Session

```text
============================================================
 CEL Verification REPL
 Type :help for commands, :quit to exit.
============================================================
cel-verifier> :var port int
Variable declared: port : int

cel-verifier> sat role == 'admin' && port > 1024

cel-verifier> :var role string
Variable declared: role : string

cel-verifier> sat role == 'admin' && port > 1024
[VERIFIED] Condition is satisfiable. Satisfying input:
  role = "admin"
  port = 1025

cel-verifier> valid port > 0 || port <= 0
[VERIFIED]

cel-verifier> valid port > 1024
[VIOLATED] Condition is violated. Counterexample input:
  port = 0

cel-verifier> equiv port > 10 <=> 10 < port
[VERIFIED]

cel-verifier> :vars
--- Session State ---
Timeout: 10s | Unroll limit: 5
Unknowns: none
Variables (2):
  role : string
  port : int

cel-verifier> :quit
Goodbye!
```

> **Note:** Inline help is built into the REPL shell. Type `:help` or
> `:help <command>` (e.g. `:help var`, `:help equiv`) at any prompt for
> detailed usage instructions and examples.

