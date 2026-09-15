# Metadata API

All of it is optional. Every result, status, duration, error and retry is reported without
a single call here — this adds only what TestNG has no concept of.

No registration is needed. The listener registers itself through TestNG's `ServiceLoader`
support, and the API finds the running test through TestNG's own thread-local
(`Reporter.getCurrentTestResult()`) — there is nothing to annotate and nothing to enable.

## Guarantees

- **Nothing here can fail your test.** No method returns an error, none throws, and every
  call is inert when no reporter is listening.
- **Calls outside a running test are dropped with a warning**, not guessed at. Attaching
  them to whichever test runs next is silent wrong data, which is worse than absent data.
  Because the thread-local is per-thread, this also applies to a call made from a thread
  the test itself spawned.
- **Warnings are emitted once per JVM**, not once per call, so a loop cannot drown the log.

## Reference

| Call | Effect |
|---|---|
| `Qualflare.label(name, value)` | A named dimension to group and filter by |
| `Qualflare.tag(tags...)` | One or more free tags; empty and null are ignored |
| `Qualflare.link(url)` | A link of type `custom` |
| `Qualflare.link(url, type, name)` | `Qualflare.ISSUE`, `Qualflare.TMS` or `Qualflare.CUSTOM` |
| `Qualflare.priority(p)` | `Qualflare.HIGH`, `MEDIUM` or `LOW` |
| `Qualflare.description(text)` | Free text shown on the case |
| `Qualflare.parameter(name, value)` | A recorded input |
| `Qualflare.maskedParameter(name)` | A recorded input whose value is **never sent** |
| `Qualflare.step(name, body)` | A timed step; steps nest |
| `Qualflare.attachment(name, path, mimeType)` | Attaches a file on disk to the case |

## Steps

```java
Qualflare.step("add to cart", () -> {
    Qualflare.parameter("sku", "widget");
    Qualflare.step("set quantity", () -> Qualflare.parameter("qty", "2"));
});
```

Timing is real elapsed time around the body. A parameter emitted inside an open step
belongs to that step; one emitted outside belongs to the case.

A step whose body throws is recorded as `failed` **and the exception still propagates**.
Swallowing it would turn a failing test green, which is the worst thing a reporter can do.

## Masked parameters take no value

```java
Qualflare.maskedParameter("token");   // there is no overload that accepts a value
```

`masked` is a display hint the server does not act on, so withholding the value here is the
only thing that actually keeps a secret out of the report. A signature that cannot accept
one cannot leak one.

## Attachments

```java
Qualflare.attachment("screenshot", Paths.get("target/shot.png"), "image/png");
Qualflare.attachment("response", Paths.get("target/body.json"), "application/json");
```

The call is **explicit**, unlike `qualflare-junit5` where the JUnit Platform's
`fileEntryPublished` hands the reporter a published file. TestNG has no such callback, so a
call here is the only way a file can reach the report.

The file is read **immediately**, not at the end of the run — a screenshot in a temp
directory the test then deletes would otherwise be gone by the time the report is written.

Two routes, chosen by media type:

- **PNG, JPEG and GIF** are copied into the report directory and referenced by name, so a
  screenshot never competes with the run's inline budget.
- **Everything else** is base64-inlined, against a per-run budget of 8 MiB of *encoded*
  bytes. Past the budget the attachment is recorded by name alone.

At most 50 attachments per case. A missing, unreadable or `null` path is **dropped
silently** — a broken path must never be the reason a build goes red.

Image copying needs `@qualflare/cli` v0.1.24 or newer, the first release that reads
`localImagePath`. Upgrade the CLI before this reporter.

## How it works

Calls go straight into an in-memory accumulator keyed by the currently running test, not
through TestNG's `Reporter.log()` — capturing arbitrary `Reporter.log()` output is out of
scope for this reporter, so nothing here interacts with whatever any other listener writes
there.

Ordering is what makes nesting work: there are no step ids, so a start/stop stack
reconstructs the tree from emission order alone, the same approach the pytest and Go
reporters use.
