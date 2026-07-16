(ns mvretailops.store
  "SSoT for the ISIC-4762 'Retail sale of music and video recordings in
  specialized stores' operations-COORDINATION actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a music/video
  specialty storefront (a record shop / video store selling vinyl, CDs,
  DVDs, Blu-rays and related physical media): sales/inventory/return
  transaction logging, floor-staff scheduling, inventory supply-order
  coordination with registered labels/distributors, and inventory-
  concern flagging (suspected counterfeit/bootleg recordings, piracy
  concerns, mis-shipment/damaged-shipment observations). It never sets
  or authorizes a shelf price, and never directly finalizes a
  copyright/licensing-dispute resolution (approving, denying, paying
  out, or otherwise settling a copyright or licensing dispute claim;
  issuing a copyright-infringement settlement, licensing-fee resolution
  or royalty payout decision) -- see `mvretailops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `stores` directory keyed by `:store-id` STRING and a
  `vendors` directory keyed by `:vendor-id` STRING (never keywords --
  consistent keying from the start, avoiding the silent-miss bug that has
  plagued earlier sibling actors).

  A registered/verified store record (business registration + retail
  license) must exist before ANY proposal targeting that store may ever
  commit or escalate -- `mvretailops.governor`'s
  `store-unverified-violations` re-derives this from the store's own
  `:registered?`/`:verified?` fields, never from proposal self-report. A
  `:coordinate-supply-order` proposal additionally names a registered
  vendor via its own `:vendor-id`; the SAME 'ground truth, not self-report'
  discipline applies via `vendor-unverified-violations`.

  The ledger stays append-only: which store a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by whom
  is always a query over an immutable log.")

(defprotocol Store
  (store-record [s store-id] "Registered store record, or nil.
    Store map: {:store-id .. :name .. :registered? bool :verified? bool}.")
  (all-store-records [s])
  (vendor-record [s vendor-id] "Registered vendor record, or nil.
    Vendor map: {:vendor-id .. :name .. :registered? bool :verified? bool}.")
  (all-vendor-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-store-records [s stores] "replace/seed the store directory (map store-id->store)")
  (with-vendor-records [s vendors] "replace/seed the vendor directory (map vendor-id->vendor)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained store/vendor directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:stores
   {"store-1" {:store-id "store-1" :name "Vinyl Vault Records & Film"
               :registered? true :verified? true}
    "store-2" {:store-id "store-2" :name "Bluewave Music & Video Exchange"
               :registered? true :verified? true}
    "store-3" {:store-id "store-3" :name "Downtown Pop-Up Media Kiosk (in intake)"
               :registered? true :verified? false}}
   :vendors
   {"vendor-1" {:vendor-id "vendor-1" :name "Meridian Music & Film Distribution"
                :registered? true :verified? true}
    "vendor-2" {:vendor-id "vendor-2" :name "Unverified Grey-Market Media Import Broker Co."
                :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (store-record [_ store-id] (get-in @a [:stores store-id]))
  (all-store-records [_] (sort-by :store-id (vals (:stores @a))))
  (vendor-record [_ vendor-id] (get-in @a [:vendors vendor-id]))
  (all-vendor-records [_] (sort-by :vendor-id (vals (:vendors @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-store-records [s stores] (when (seq stores) (swap! a assoc :stores stores)) s)
  (with-vendor-records [s vendors] (when (seq vendors) (swap! a assoc :vendors vendors)) s))

(defn seed-db
  "A MemStore seeded with the demo store/vendor directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `stores`/`vendors` maps (store-id/
  vendor-id string -> record map) -- the primary test/dev entry point.
  Either may be empty (an unregistered-everywhere store)."
  ([stores] (mem-store stores {}))
  ([stores vendors]
   (->MemStore (atom {:stores (or stores {}) :vendors (or vendors {})
                       :ledger [] :coordination-log []}))))
