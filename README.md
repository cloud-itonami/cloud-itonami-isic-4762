# cloud-itonami-isic-4762

Open Business Blueprint for **ISIC Rev.5 4762**: retail sale of music and
video recordings in specialized stores -- vinyl records, CDs, DVDs,
Blu-rays and related physical media sold through a dedicated specialty
storefront (a record shop / video store).

This repository publishes a music/video-recordings-retail
operations-COORDINATION actor -- sales/inventory/return transaction
logging, floor-staff scheduling, inventory supply-order coordination
with registered labels/distributors, and inventory-concern flagging
(suspected counterfeit/bootleg recordings, piracy concerns, mis-shipment
observations) -- as an OSS business that any qualified operator can
fork, deploy, run, improve and sell, so an independent music/video
specialty store never surrenders its operations data to a closed
back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **MusicVideoRetailAdvisor
⊣ MusicVideoRetailGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:music-video-retail-governor`, is
a distinct, independent build (no naming-collision precedent question --
distinct from ISIC 4742's own `:audio-video-retail-governor` and ISIC
4719's own `:merchandise-retail-governor`).

> **Why an actor layer at all?** An LLM is great at drafting a sales-
> record summary, a staffing proposal, or a supply-order request -- but
> it has no license to actually finalize a copyright/licensing-dispute
> decision against a customer, distributor or rights-holder, no way to
> independently confirm a store or a supply-order vendor is actually a
> registered/verified counterparty, and no notion of when a "flag this
> concern" op quietly turns into a claim to have already acted on it.
> Letting it act directly invites an unverified store's data entering
> the ledger, an unverified (possibly grey-market/counterfeit) vendor
> receiving a media-recordings order, or -- worst of all -- a fabricated
> claim to have approved, denied or paid out a copyright/licensing
> dispute, exposing the shop and its staff to real liability. This
> project seals the MusicVideoRetailAdvisor into a single node and wraps
> it with an independent **MusicVideoRetailGovernor**, a human
> **approval workflow**, and an immutable **audit ledger**.

## Scope: coordination only, not copyright/licensing-dispute authority

This actor is **operations coordination only**. It never performs or
authorizes:

- setting or overriding a shelf/unit price
- directly finalizing a copyright/licensing-dispute resolution
  (approving, denying, paying out, or otherwise settling a copyright or
  licensing dispute claim; issuing a copyright-infringement settlement,
  licensing-fee resolution or royalty payout decision)
- copyright/licensing-authority enforcement (unilaterally instructing a
  vendor or rights-holder to honor or deny a claim on the store's
  behalf)

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging an inventory
concern for a human to triage is exactly this actor's job --
`:flag-inventory-concern` is never excluded by this check, only
FINALIZING/settling/paying-out a copyright or licensing dispute is.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`mvretailops.governor`'s `effect-not-propose-violations` HARD check and
`mvretailops.phase`'s phase table, which never puts
`:flag-inventory-concern` in any phase's `:auto` set). A human store
operator is always the one who actually acts on a flagged concern or
confirms a high-cost supply order.

## The core contract

```
store/vendor registration + operations-coordination request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ MusicVideoRetail-     │ ─────────────▶ │ MusicVideoRetailGovernor     │  (independent system)
   │ Advisor (sealed)      │  + citations    │ store-unverified ·          │
   └───────────────────────┘                 │ vendor-unverified ·         │
          │                 commit ◀┼ effect-not-propose ·               │
          │                         │ scope-excluded (copyright/           │
    record + ledger        escalate ┼ licensing-dispute finalization) ·    │
          │              (ALWAYS for│ op-not-allowed                      │
          │       :flag-inventory-  │                                      │
          │       concern/high-cost └────────────────────────────┘
          │       supply-order)
          ▼
      human approval
```

**The MusicVideoRetailAdvisor never commits a proposal the
MusicVideoRetailGovernor would reject, and an inventory-concern flag or a
high-cost supply order never commits without a human sign-off.** Hard
violations (an unregistered/unverified store; an unregistered/unverified
supply-order vendor; a non-`:propose` effect; content touching
copyright/licensing-dispute-finalization; an op outside the closed
allowlist) force **hold** and *cannot* be approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: shelfing, picking, restocking,
point-of-sale handling) under human/robot floor operations gated by store
policy. This actor itself does not dispatch robot/hardware actions -- it
is strictly the operations-coordination layer (sales-record logging,
staffing scheduling, supply-order coordination, inventory-concern
flagging) any physical-dispatch layer could eventually feed proposals
into, always gated the same way by the independent
MusicVideoRetailGovernor.

## Features

- **Closed proposal-op allowlist**: `log-sales-record`,
  `schedule-staffing-operation`, `coordinate-supply-order`,
  `flag-inventory-concern` (all `:effect :propose`).
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Store unverified** -- the target store's business registration
     must exist AND be independently registered/verified in the store.
  2. **Vendor unverified** -- for `:coordinate-supply-order` only, the
     named vendor must exist AND be independently registered/verified --
     a supply-chain counterparty-verification gate that matters doubly
     here, where a grey-market/counterfeit media import broker is
     exactly the kind of unverified counterparty this actor must never
     quietly transact with.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** -- directly finalizing a copyright/licensing-
     dispute resolution (approval, denial, payout, settlement) and an op
     outside the closed allowlist are both permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-inventory-concern` -- ALWAYS escalates, regardless of
    confidence or phase. A "flag a concern" op is never auto-commit
    eligible and never finalizes a copyright/licensing decision itself --
    it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold -- a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: sales-record logging only (approval-gated)
  - Phase 2: + staffing scheduling, supply-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (inventory concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/mvretailops/governor_test.clj` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/mvretailops/advisor_test.clj` -- advisor proposal shape and
  consistency
- `test/mvretailops/phase_test.clj` -- rollout phase logic
- `test/mvretailops/governor_contract_test.clj` -- full graph
  integration, audit trail
- `test/mvretailops/store_contract_test.clj` -- Store protocol and
  MemStore implementation

### Modules

- `mvretailops.store` -- SSoT (MemStore, String-keyed store/vendor
  directories, append-only ledger)
- `mvretailops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `mvretailops.governor` -- independent compliance layer
- `mvretailops.phase` -- staged rollout (0→3)
- `mvretailops.operation` -- langgraph-clj StateGraph
- `mvretailops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4762`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Sales/inventory/return transaction logging (`:log-sales-record`) | Real POS/inventory-system integration |
| Floor-staff scheduling coordination (`:schedule-staffing-operation`) | Direct staff time-clock/payroll integration |
| Music/video-recordings supply-order coordination with a registered, verified vendor, HARD-gated on vendor verification and a double-actuation-free single-proposal shape (`:coordinate-supply-order`) | Real supplier-ordering-system integration |
| Inventory-concern flagging (suspected counterfeit/bootleg recording, piracy concern, mis-shipment/damaged-shipment observation), ALWAYS human-gated (`:flag-inventory-concern`) | Directly finalizing any copyright/licensing-dispute decision -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Daily reconciliation/cash-up -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a return-
authorization or a delivery-damage-escalation check) as its own governed
op with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship checks already establish.

## Maturity

`:implemented` -- `MusicVideoRetailAdvisor` + `MusicVideoRetailGovernor`
run as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet, with
its own distinct, independently-named governor and its own supply-chain
vendor-verification check tuned to the grey-market/counterfeit-media-
import risk this vertical faces.

## License

Code and implementation templates are AGPL-3.0-or-later.
