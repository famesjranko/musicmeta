package com.landofoz.musicmeta.testkit

/**
 * A contract every implementation of one interface owes its callers. A subclass supplies a subject
 * and nothing else; the rules live in the contract base built on top of this, so a new
 * implementation inherits them by existing.
 *
 * **It lives in `src/testFixtures`, not `src/test`, and that is the whole reason that source set was
 * enabled.** A class in `musicmeta-core/src/test` is invisible to `musicmeta-android` and
 * `musicmeta-okhttp`, which depend on the published artifact only — so a cache contract with a Room
 * subclass, or an `HttpClient` contract with an OkHttp subclass, could not be shared from there.
 * There is deliberately no second, weaker copy for the contracts whose subclasses all stay inside
 * core: one shape, so a contract does not have to change source sets on the day its first
 * cross-module subclass arrives.
 *
 * **A consuming module sees only the public surface here.** `src/testFixtures` is associated with
 * core's main compilation and can read core's `internal` declarations, but `musicmeta-android` and
 * `musicmeta-okhttp` consume it as an ordinary dependency and cannot. A contract base is free to use
 * an `internal` type inside a method body; it must not put one in a signature a subclass implements.
 *
 * Naming, so a failure names the implementation: the base is `<Interface>Contract` — abstract, no
 * `Test` suffix, not runnable — and a subclass is `<Implementation>ContractTest`.
 *
 * **No implementation may override a contract test.** A legitimate inability to satisfy a rule is a
 * finding for that contract's ticket, never an `@Ignore` and never an override. Implementation-
 * specific tests stay in the existing per-implementation file.
 */
abstract class ContractSuite<T> {

    /** A fresh subject. Called once per test, never shared between them. */
    protected abstract fun subject(): T

    /** Optional per-test teardown for a subject that holds a resource — a server, a database. */
    protected open fun release(subject: T) {}
}
