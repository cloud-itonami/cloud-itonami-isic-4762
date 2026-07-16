(ns mvretailops.governor
  "MusicVideoRetailGovernor -- the independent compliance layer that earns
  the MusicVideoRetailAdvisor the right to commit. The advisor has no
  notion of whether a store is actually registered and license-verified,
  whether a named supply-order vendor is itself a registered/verified
  counterparty, whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently drifted
  into a permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY (sales/
  inventory/return transaction logging, floor-staff scheduling, inventory
  supply-order coordination with registered labels/distributors,
  inventory-concern flagging). It NEVER performs or authorizes:
    - setting or overriding a shelf/unit price
    - directly finalizing a copyright/licensing-dispute resolution
      (approving, denying, paying out, or otherwise settling a copyright
      or licensing dispute claim; issuing a copyright-infringement
      settlement, licensing-fee resolution or royalty payout decision)
    - copyright/licensing-authority enforcement (unilaterally instructing
      a vendor or rights-holder to honor or deny a claim on the store's
      behalf)

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Store unverified           -- the target store record must exist
                                     AND be independently confirmed
                                     `:registered?`/`:verified?` in the
                                     store before ANY proposal for it may
                                     commit or even escalate. Never trusts
                                     a proposal's own claim about the
                                     store -- re-derived from the store's
                                     own record, the same 'ground truth,
                                     not self-report' discipline every
                                     sibling actor's governor uses.
    2. Vendor unverified          -- for `:coordinate-supply-order` ONLY,
                                     the proposal's own drafted `:value`
                                     must name a `:vendor-id` that
                                     resolves to an independently
                                     `:registered?`/`:verified?` vendor
                                     record. A missing vendor-id, or one
                                     that resolves to an unregistered or
                                     unverified vendor, is a HARD block --
                                     a supply-chain counterparty-
                                     verification gate that matters
                                     doubly in this vertical, where a
                                     grey-market/counterfeit media import
                                     broker is exactly the kind of
                                     unverified counterparty this actor
                                     must never quietly transact with.
    3. Effect not :propose        -- every proposal's `:effect` MUST be
                                     `:propose`. Any other effect value
                                     is, by construction, a claim to
                                     directly actuate/commit outside
                                     governance -- HARD block, not merely
                                     low-confidence.
    4. Scope exclusion            -- ANY proposal (regardless of op)
                                     whose op, summary, rationale, cites
                                     or draft value touches directly
                                     finalizing a copyright/licensing-
                                     dispute resolution (approving,
                                     denying, paying out, or otherwise
                                     settling a copyright or licensing
                                     dispute claim; issuing a copyright-
                                     infringement settlement, licensing-
                                     fee resolution or royalty payout
                                     decision) is a HARD, PERMANENT block
                                     -- this actor's charter excludes that
                                     territory structurally, not as a
                                     rollout milestone. Evaluated
                                     UNCONDITIONALLY on every proposal. An
                                     op outside the closed four-op
                                     allowlist is the SAME failure mode
                                     (an advisor proposing something it
                                     was never authorized to propose) and
                                     is folded into this same check.
                                     `:flag-inventory-concern` itself is
                                     never excluded by this check --
                                     surfacing a suspected counterfeit/
                                     bootleg-recording, piracy or mis-
                                     shipment concern for a human is
                                     exactly this actor's job; only
                                     FINALIZING/settling/paying-out a
                                     copyright or licensing dispute is
                                     excluded (see `scope-excluded-terms`
                                     below -- phrased as the finalization/
                                     execution ACTION, never a bare noun
                                     like 'copyright', 'piracy' or
                                     'counterfeit', so the default mock
                                     advisor's own `:flag-inventory-
                                     concern` rationale never self-trips
                                     this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-inventory-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `mvretailops.phase` independently agrees:
      `:flag-inventory-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      music/video-recordings procurement proposal always needs a human
      sign-off, even when the governor and phase would otherwise allow
      auto-commit."
  (:require [clojure.string :as str]
            [mvretailops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example single-store music/video-recordings procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-supply-order` proposal citing an
  `:estimated-cost` above this value ALWAYS escalates to human sign-off,
  regardless of confidence or rollout phase."
  1000.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`)."
  #{:log-sales-record :schedule-staffing-operation
    :coordinate-supply-order :flag-inventory-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-inventory-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  copyright/licensing-dispute resolution (approving, denying, paying out,
  or otherwise settling a copyright or licensing dispute claim; issuing a
  copyright-infringement settlement, licensing-fee resolution or royalty
  payout decision) or otherwise acting on an inventory concern rather
  than merely flagging it for a human. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'approved the copyright claim', 'processed the royalty
  payment'), never a bare noun like 'copyright', 'license', 'piracy' or
  'counterfeit' -- a bare noun would accidentally match inside this
  actor's own legitimate `:flag-inventory-concern` default proposal text
  (whose whole job is to talk about suspected counterfeit/bootleg
  recordings, piracy concerns and mis-shipments) and self-block the happy
  path. See
  `mvretailops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["finalize the copyright dispute" "finalized the copyright dispute" "finalizes the copyright dispute"
   "finalize the licensing dispute" "finalized the licensing dispute" "finalizes the licensing dispute"
   "finalize the copyright claim decision" "finalized the copyright claim decision"
   "approve the copyright claim" "approved the copyright claim" "approving the copyright claim"
   "deny the copyright claim" "denied the copyright claim" "denying the copyright claim"
   "reject the copyright claim" "rejected the copyright claim" "rejecting the copyright claim"
   "settle the copyright dispute" "settled the copyright dispute" "settling the copyright dispute"
   "settle the licensing dispute" "settled the licensing dispute" "settling the licensing dispute"
   "authorize the royalty payout" "authorized the royalty payout" "authorizing the royalty payout"
   "pay out the licensing settlement" "paid out the licensing settlement" "paying out the licensing settlement"
   "issue a copyright infringement settlement" "issued a copyright infringement settlement" "issuing a copyright infringement settlement"
   "process the royalty payment" "processed the royalty payment" "processing the royalty payment"
   "confirm the licensing dispute resolution" "confirmed the licensing dispute resolution"
   "execute the copyright settlement" "executed the copyright settlement"
   "著作権紛争を確定" "著作権紛争を確定した" "ライセンス紛争の解決を承認" "著作権侵害の和解を承認した"
   "著作権請求を却下した" "著作権請求を拒否した" "ロイヤルティの支払いを実行" "ロイヤルティを支払った"
   "ライセンス和解を承認した" "ライセンス和解を実行した" "著作権和解処理を実行した" "著作権和解処理を実行"])

;; ----------------------------- checks -----------------------------

(defn- store-unverified-violations
  "The target store must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the proposal's
  own `:store-id` claim without a store lookup."
  [{:keys [store-id]} st]
  (let [s (store/store-record st store-id)]
    (when-not (and s (:registered? s) (:verified? s))
      [{:rule :store-unverified
        :detail (str store-id " は未登録または未検証の店舗 -- いかなる提案も進められない")}])))

(defn- vendor-unverified-violations
  "For `:coordinate-supply-order` ONLY, the proposal's own drafted
  `:value` must name a `:vendor-id` that resolves to an independently
  `:registered?`/`:verified?` vendor record. A missing vendor-id, or one
  that resolves to an unregistered/unverified vendor, is a HARD block --
  never trust the proposal's own vendor claim without a store lookup, the
  SAME 'ground truth, not self-report' discipline as
  `store-unverified-violations`, reapplied to the supply-chain
  counterparty."
  [proposal st]
  (when (= :coordinate-supply-order (:op proposal))
    (let [vendor-id (get-in proposal [:value :vendor-id])
          v (and vendor-id (store/vendor-record st vendor-id))]
      (when-not (and v (:registered? v) (:verified? v))
        [{:rule :vendor-unverified
          :detail (str (or vendor-id "(vendor-id missing)")
                        " は未登録または未検証の仕入先 -- 発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing a copyright/licensing-
  dispute resolution (approval/denial/payout/settlement), regardless of
  confidence or how clean every other check is. Evaluated UNCONDITIONALLY
  on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "著作権/ライセンス紛争の承認・却下・支払い・和解確定など権利紛争確定行為(copyright/licensing-dispute finalization)に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost` above
  `supply-cost-threshold` -- always needs human sign-off (SOFT escalate,
  not a hard block: the order itself is in scope, only its size requires
  a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a MusicVideoRetailAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [store-id (or (:store-id proposal) (:store-id request))
        hard (into []
                   (concat (store-unverified-violations {:store-id store-id} store)
                           (vendor-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :store-id   (:store-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
