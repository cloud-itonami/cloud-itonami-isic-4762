(ns mvretailops.advisor
  "MusicVideoRetailAdvisor -- the *contained intelligence node* for the
  ISIC-4762 'Retail sale of music and video recordings in specialized
  stores' operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: sales/inventory/return transaction logging, floor-staff
  scheduling, inventory supply-order coordination (label/distributor
  orders), and inventory-concern flagging (suspected counterfeit/bootleg
  recordings, piracy concerns, mis-shipment/damaged-shipment
  observations). CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream by
  `mvretailops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a shelf/unit-price decision, or a direct
  copyright/licensing-dispute-finalization action (approving, denying,
  paying out, or otherwise settling a copyright or licensing dispute
  claim; issuing a copyright-infringement settlement, licensing-fee
  resolution or royalty payout decision) -- those are permanently out of
  scope for this actor, not merely un-implemented. `mvretailops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :store-id   str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-sales-record
  "Draft a sales/inventory/return transaction log entry. Pure logging of
  observed transactions (units sold, returns processed, stock-count
  deltas) -- never a shelf/unit-price decision, and never a copyright/
  licensing-dispute finalization."
  [_db {:keys [store-id patch]}]
  {:op         :log-sales-record
   :store-id   store-id
   :summary    (str store-id " の販売/在庫/返品記録を記録: " (pr-str (keys patch)))
   :rationale  "販売数量・在庫カウント・返品処理の観察記録のみ。値付けや著作権紛争の判断は含まない。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.93})

(defn- propose-staffing-operation
  "Draft a floor-staff scheduling proposal (a roster/calendar entry,
  never a direct enforcement or copyright/licensing-dispute action)."
  [_db {:keys [store-id patch]}]
  {:op         :schedule-staffing-operation
   :store-id   store-id
   :summary    (str store-id " のフロアスタッフ予定を提案: " (pr-str (keys patch)))
   :rationale  "フロア/レジのシフト調整提案のみ。人員の最終配置は人間が確定する。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft a music/video-recordings inventory procurement coordination
  request naming a registered label/distributor vendor -- never a
  finalized purchase order; a human always confirms procurement."
  [_db {:keys [store-id patch]}]
  {:op         :coordinate-supply-order
   :store-id   store-id
   :summary    (str store-id " 向け音楽・映像ソフト在庫の発注調整を提案: " (pr-str (keys patch)))
   :rationale  "レーベル・ディストリビューター等の仕入先発注調整提案のみ。確定発注は人間が行う。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.90})

(defn- propose-inventory-concern
  "Surface an observed inventory concern (suspected counterfeit/bootleg
  recording, piracy concern, mis-shipment or damaged-shipment
  observation) for HUMAN triage. This op ALWAYS escalates in
  `mvretailops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real.
  Deliberately reports the OBSERVATION only, never a finalization/
  approval/denial/payout action on a copyright or licensing dispute, so
  the default rationale never trips the governor's `scope-excluded-terms`
  (see that var's docstring)."
  [_db {:keys [store-id patch]}]
  {:op         :flag-inventory-concern
   :store-id   store-id
   :summary    (str store-id " の在庫懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "模倣品(海賊版)疑い・誤出荷・破損出荷の観察事実の報告。常に人間の確認・判断が必要。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-sales-record (propose-sales-record _db request)
                   :schedule-staffing-operation (propose-staffing-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-inventory-concern (propose-inventory-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually approved the copyright claim and processed the royalty payment")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :store-id (:store-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
