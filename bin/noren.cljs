#!/usr/bin/env nbb
;; loop-noren CLI —— この repo で**外に出る唯一の場所**。
;;
;;   nbb bin/noren.cljs tick                 # 1 周（既定は dry-run。送らない）
;;   nbb bin/noren.cljs tick --submit        # 承認キューへ effect を積む
;;   nbb bin/noren.cljs diagnose <url> [--catalog]
;;   nbb bin/noren.cljs preview <prospect-id>
;;   nbb bin/noren.cljs build <brief.edn> [--out site.html]
;;
;; classpath は README の 1 行、または scripts/run.cljs。
;;
;; ## なぜ取得を先にまとめてやるか
;;
;; `loop-noren.loop/tick` は純関数で、`:fetch` に**同期の関数**を要求する。
;; JS の promise を同期的に開くことはできないので、ここで先に全部取得して
;; map に落とし、loop には lookup を渡す。取得件数が提案件数より多くなるが、
;; prospect は人が宣言した小さな列なので実害が無い（自動発見を繋ぐときは
;; ここを段階取得に変える —— loop 側は変えなくてよい）。
(ns loop-noren.cli
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [promesa.core :as p]
            ["fs" :as fs]
            ["path" :as path]
            [noren.diagnose :as diagnose]
            [noren.prescribe :as prescribe]
            [noren.build :as build]
            [loop-noren.registry :as registry]
            [loop-noren.store :as store]
            [loop-noren.store-io :as store-io]
            [loop-noren.discover :as discover]
            [loop-noren.murakumo :as murakumo]
            [loop-noren.sources :as sources]
            [loop-noren.loop :as l]))

(def root (path/resolve (path/dirname *file*) ".."))
(defn- at [& xs] (apply path/join root xs))
(defn- slurp* [f] (when (fs/existsSync f) (.readFileSync fs f "utf8")))
(defn- read-edn [f] (some-> (slurp* f) edn/read-string))

(def now-iso (.toISOString (js/Date.)))
(def today (subs now-iso 0 10))
(def year (js/parseInt (subs now-iso 0 4)))

(def sender
  "法 4 条の表示。環境変数で上書きできるが、**既定でも欠けていない**
  （欠けたまま送れる既定を置かない）。"
  {:name (or js/process.env.NOREN_SENDER_NAME "cloud-itonami（AWAI Network, L.L.C.）")
   :address (or js/process.env.NOREN_SENDER_ADDRESS "https://itonami.cloud/ 記載の所在地")
   :opt-out-contact (or js/process.env.NOREN_OPT_OUT "stop@itonami.cloud")})

;; ── I/O ──────────────────────────────────────────────────────────────────

(defn fetch-site
  "公開面を取得する。**失敗を成功に見せない** —— 落ちたら `:html` は nil で、
  `diagnose` は `:absent` を返す（0 点のサイトとは別物）。"
  [url]
  (-> (js/fetch url #js {:redirect "follow"
                         :headers #js {"user-agent"
                                       "loop-noren/0.1 (+https://itonami.cloud/cloud-itonami/loop-noren)"}})
      (p/then (fn [r] (p/let [t (.text r)] {:html t :status (.-status r)})))
      (p/catch (fn [e] {:html nil :status (str "error: " (.-message e))}))))

(defn prefetch
  "url の列 → `{url {:html :status}}`。"
  [urls]
  (p/let [rs (p/all (map fetch-site urls))]
    (zipmap urls rs)))

(defn journal-append!
  "決定事実を journal 面へ 1 行。**raw は sha256 で参照する**（生返答も HTML も
  ここには入らない —— 実体は raw 面に居り、receipt が join する）。"
  [entry]
  (store-io/append-lines! root (store/journal-shard now-iso)
                          [(store/journal-entry entry now-iso)]))

(defn receipt-append! [r]
  (store-io/append-lines! root (store/corpus-shard now-iso) [r]))

(defn archive!
  "本文/生返答を raw 面へ置き、receipt を 1 件書く。→ receipt に載った
  `{:path :sha256 :bytes}`（何も書かなければ nil）。"
  [{:keys [subject kind url content ext text-source status error]}]
  (let [raw (store-io/write-raw! root {:content content :kind kind :ext ext :at now-iso})]
    (receipt-append! (store/receipt (merge {:at now-iso :subject subject :kind kind
                                            :url url :text-source text-source
                                            :status status :error error}
                                           raw)))
    raw))

(defn history-for
  "過去に**提案として出した**記録だけを履歴とする（hold は接触ではない）。"
  [key]
  (if-not (fs/existsSync (at "journal"))
    []
    (->> (fs/readdirSync (at "journal"))
         (filter #(str/ends-with? % ".edn"))
         (mapcat (fn [f] (->> (str/split-lines (or (slurp* (at "journal" f)) ""))
                              (remove str/blank?)
                              (map edn/read-string))))
         (filter #(and (= key (:prospect %)) (= :proposed (:outcome %))))
         (mapv (fn [e] {:contacted-at (subs (str (:at e)) 0 10)})))))

(defn propose!
  "承認キューへ 1 件提案する。**送信ではない。**

  経路は cloud-itonami の narrow ingress `POST /api/{org}/{repo}/noren` で、
  `kaizen` ingress（回遊 → 改善、`kaiyu` が使っている実在の経路）と同じ形にする:
  提案しかできない per-tenant の鍵、`202 proposed` / `200 already-open`、
  承認は cockpit の人がやる。**この loop は approve の鍵を持たない。**

  ⚠ **server 側はまだ無い**（2026-08-11 実測: `POST /api/{org}/{repo}/effects` は
  502、effect を外から作る公開経路は cloud-itonami に存在せず、
  `effectsOnRequestPost` は approve/reject 専用）。したがってここは
  **contract を先に書いた状態**で、404/502 が返ったら『未提供』と明示して
  journal に残す —— 提案できなかったことを、提案したことにしない。"
  [result]
  (let [org (or js/process.env.NOREN_TENANT_ORG "cloud-itonami")
        repo (or js/process.env.NOREN_TENANT_REPO "loop-noren")
        key js/process.env.NOREN_INGRESS_KEY]
    (if-not key
      (do (println "  ! NOREN_INGRESS_KEY が無い（提案は journal と receipt にだけ残る）")
          {:queued false :reason :no-ingress-key})
      (-> (js/fetch (str "https://itonami.cloud/api/" org "/" repo "/noren")
                    #js {:method "POST"
                         :headers #js {"content-type" "application/json"
                                       "authorization" (str "Bearer " key)}
                         :body (js/JSON.stringify
                                ;; server 側の contract（cloud-itonami.noren/validate）:
                                ;; id / title / body / site / claimAxes[] が必須で、
                                ;; **claimAxes が空なら 400**。receipt が持っている
                                ;; 測定軸をそのまま渡す —— ここで別に組み立てると、
                                ;; 送った主張と残した証跡がずれる。
                                (clj->js {:id (str "noren:" (:prospect result) ":" today)
                                          :title (get-in result [:message :subject])
                                          :body (get-in result [:message :body])
                                          :site (:prospect result)
                                          :claimAxes (vec (:receipt/claim-axes (:receipt result)))
                                          :overall (:receipt/overall (:receipt result))
                                          :action (some-> (:receipt/action (:receipt result)) name)}))})
          (p/then (fn [r]
                    (let [st (.-status r)]
                      (cond
                        (= 202 st) {:queued true :status st}
                        (= 200 st) {:queued true :status st :note :already-open}
                        ;; 503 = テナントに ingress 鍵が未設定（provisioned でない）。
                        ;; 404/502 = 経路そのものがまだデプロイされていない。
                        ;; **どれも「提案した」ではない。**
                        (#{404 502 503} st)
                        (do (println (str "  ! ingress が使えない（HTTP " st "）—— 鍵未設定か未デプロイ"))
                            {:queued false :status st :reason :ingress-unavailable})
                        :else {:queued false :status st}))))
          (p/catch (fn [e] {:queued false :reason (.-message e)}))))))

;; ── commands ─────────────────────────────────────────────────────────────

(defn- prospects []
  (let [{:keys [ok rejected]} (registry/validate (read-edn (at "data" "prospects.edn")) today)]
    (doseq [r rejected] (println "  ! 登録不備:" (:prospect r) (str/join "," (map name (:problems r)))))
    (registry/dedup ok)))

(defn- run-tick [ps {:keys [submit? record?]}]
  (p/let [pages (prefetch (map :prospect/site-url ps))]
    ;; **測った HTML を残す。** claim は測定に拘束されているので、その測定の
    ;; 入力が残っていなければ、後から「本当にそう書いてあったか」を引けない。
    (let [raws (when record?
                 (into {} (map (fn [[url {:keys [html status]}]]
                                 [url (archive! {:subject (-> url
                                                              (str/replace #"^https?://" "")
                                                              (str/replace #"^www\." "")
                                                              (str/split #"[/?#]") first)
                                                 :kind :page :ext "html" :url url
                                                 :content html :status status
                                                 :text-source {:kind :live}})])
                               pages)))]
      (l/tick {:prospects ps :now today :year year :sender sender
               :io (cond-> {:fetch (fn [url] (get pages url {:html nil :status "not-fetched"}))
                            :history history-for
                            :suppression #(set (or (read-edn (at "data" "suppression.edn")) #{}))}
                     record? (assoc :record!
                                    (fn [r]
                                      (journal-append!
                                       (let [url (some #(when (= (:prospect r)
                                                                 (-> % (str/replace #"^https?://" "")
                                                                     (str/replace #"^www\." "")
                                                                     (str/split #"[/?#]") first))
                                                          %)
                                                       (keys pages))]
                                         (cond-> r
                                           (get raws url) (assoc :raw (get raws url)))))))
                     submit? (assoc :propose! propose!))}))))

(defn cmd-tick [args]
  (let [submit? (boolean (some #{"--submit"} args))]
    (println (str "tick " today (if submit? " [submit]" " [dry-run — 何も送らない]")))
    (p/let [{:keys [results proposed examined]} (run-tick (prospects) {:submit? submit? :record? true})]
      (doseq [r results]
        (println (str "  " (:prospect r) " → " (name (or (:outcome r) :?))
                      (when (:verdict r) (str " / " (name (:verdict r)) " " (int (:overall r)) "点"))
                      (when (seq (:violations r)) (str " / hold: " (str/join "," (map name (:violations r)))))
                      (when (seq (:warnings r)) (str " / warn: " (str/join "," (map name (:warnings r))))))))
      (println (str "examined " examined " / proposed " proposed)))))

(defn cmd-diagnose [[url & flags]]
  (p/let [{:keys [html status]} (fetch-site url)]
    (let [d (diagnose/diagnose html {:surface (if (some #{"--catalog"} flags) :catalog :storefront)
                                     :url url :year year})]
      (println (str url " → HTTP " status))
      (pp/pprint (-> d (dissoc :axes) (update :findings #(mapv (juxt :id :score :finding) %))))
      (println "\n処方:")
      (pp/pprint (dissoc (prescribe/prescribe d) :diagnosis)))))

(defn cmd-preview [[id]]
  (if-let [p* (first (filter #(= id (:prospect/id %)) (prospects)))]
    (p/let [{:keys [results]} (run-tick [p*] {:submit? false :record? false})]
      (let [r (first results)]
        (pp/pprint (dissoc r :message :prescription))
        (when-let [m (:message r)]
          (println (str "\n--- 件名 ---\n" (:subject m) "\n--- 本文 ---\n" (:body m))))))
    (println "そのような prospect が data/prospects.edn に無い:" id)))

(defn cmd-build [[brief-file & flags]]
  (let [brief (read-edn brief-file)
        css (.readFileSync fs (at ".." ".." "kotoba-lang" "jp-go-digital-design-system"
                                  "resources" "jp_go_dds" "dds.css") "utf8")
        {:keys [html missing]} (build/site (merge {:year year} brief) {:css css})
        out (or (second (drop-while #(not= "--out" %) flags)) "site.html")]
    (.writeFileSync fs out html)
    (println "wrote" out)
    (when (seq missing)
      (println "所有者から貰うまで埋めない事実:" (str/join ", " (map name missing))))))

;; ── discover ─────────────────────────────────────────────────────────────

(defn- area [id]
  (let [{:keys [areas]} (read-edn (at "data" "areas.edn"))
        enabled (filter :enabled? areas)]
    (if id
      (first (filter #(= (keyword id) (:id %)) areas))
      (first enabled))))

(defn ^:private append-prospects!
  "受理された prospect を名簿に追記する。**既存を書き換えない**（追記のみ）。"
  [ps]
  (let [f (at "data" "prospects.edn")
        cur (vec (or (read-edn f) []))
        merged (into cur ps)]
    (.writeFileSync fs f (with-out-str (pp/pprint merged)))
    (count merged)))

(defn cmd-discover [args]
  (let [accept? (boolean (some #{"--accept"} args))
        id (first (remove #(str/starts-with? % "--") args))
        a (area id)]
    (if-not a
      (println "有効な区画が data/areas.edn に無い（:enabled? true が 1 つも無いか、id が違う）")
      (let [target (murakumo/resolve-target)
            ;; 既に名簿に在る host は読まない（LLM も呼ばない、相手も叩かない）
            known (set (map (fn [p] (-> p :prospect/site-url
                                        (str/replace #"^https?://" "")
                                        (str/replace #"^www\." "")
                                        (str/split #"[/?#]") first))
                            (or (read-edn (at "data" "prospects.edn")) [])))]
        (println (str "discover " (:id a) " — " (:label a)))
        (println (str "  model: " (:model target) " via " (name (:via target))
                      " → " (:endpoint target)
                      (when (:alias-for target) (str "（今の実体: " (:alias-for target) "）"))))
        (p/let [{:keys [candidates without-website chain-outlets unclassified raw-count]}
                (sources/fetch-candidates (:bbox a))]
          (println (str "  OSM: " raw-count " element → candidate " (count candidates)
                        "（website 無し " without-website
                        " / チェーン店舗 " chain-outlets
                        " / 分類不能 " unclassified "）"))
          (let [{:keys [accepted results examined remaining]}
                (discover/run {:candidates candidates
                               :now today
                               :model (str (:model target)
                                           (when (:alias-for target) (str "=" (:alias-for target))))
                               :known known
                               :io (let [page-text (sources/page-text-fn)
                                         complete (murakumo/complete-fn target)
                                         seen (atom {})]
                                     {:page-text
                                      (fn [url]
                                        (let [{:keys [text source] :as r} (page-text url)]
                                          (swap! seen assoc :page
                                                 (archive! {:subject url :kind :page-text :ext "txt"
                                                            :url url :content text
                                                            :text-source source
                                                            :status (:status source)}))
                                          r))
                                      :complete
                                      (fn [prompt system]
                                        (let [reply (complete prompt system)]
                                          (swap! seen assoc :reply
                                                 (archive! {:subject (get-in @seen [:page :sha256])
                                                            :kind :llm-reply :ext "txt"
                                                            :content reply
                                                            :text-source {:kind :murakumo}
                                                            :error @murakumo/last-error}))
                                          reply))
                                      :record!
                                      (fn [r]
                                        (journal-append!
                                         (cond-> (assoc r :kind :discover)
                                           (:page @seen) (assoc :raw (:page @seen))
                                           (:reply @seen) (assoc :raw-reply-ref (:reply @seen))))
                                        (reset! seen {}))})})]
            (doseq [r results]
              (println (str "  " (:candidate r) " → " (name (:decision r))
                            (when-let [s (get-in r [:text-source :kind])] (str " / 本文:" (name s)))
                            (when (seq (:dropped r)) (str " / 照合で落ちた: " (str/join "," (map name (:dropped r)))))
                            (when (seq (:violations r)) (str " / " (str/join "," (map (comp name :rule) (:violations r))))))))
            (when-let [e @murakumo/last-error] (println (str "  ! LLM: " e)))
            (println (str "examined " examined " / accepted " (count accepted) " / remaining " remaining))
            (if (and accept? (seq accepted))
              (println (str "data/prospects.edn に追記した（計 " (append-prospects! accepted) " 件）"))
              (doseq [p accepted]
                (println (str "\n--- 受理（--accept で名簿に追記）---\n"
                              (with-out-str (pp/pprint p))))))))))))

(defn -main [args]
  (case (first args)
    "tick" (cmd-tick (rest args))
    "discover" (cmd-discover (rest args))
    "verify-corpus"
    (let [receipts (store-io/all-receipts root)
          res (store/verify receipts (store-io/stat-fn root))
          v (store/verdict res)]
      (println (str "receipts " (count receipts)
                    " / ok " (:ok res)
                    " / raw 無し " (:no-raw res)
                    " / annex から drop 済み " (count (:absent res))
                    " / 欠落 " (count (:missing res))
                    " / 不一致 " (count (:mismatch res))))
      (doseq [m (:mismatch res)] (println "  ! 不一致:" (:path m)))
      (doseq [m (:missing res)] (println "  ! 欠落:" m))
      (println (str "verdict: " (name v)
                    "（identity のみ。custody は `git annex find --in kotobase raw/` が答える）"))
      (js/process.exit (if (= :ok v) 0 1)))

    "llm-probe" (let [t (murakumo/resolve-target)
                      f (murakumo/complete-fn t)]
                  (println "target:" (pr-str t))
                  (println "reply:" (pr-str (f "pong とだけ返してください。" nil)))
                  (when-let [e @murakumo/last-error] (println "error:" e)))
    "diagnose" (cmd-diagnose (rest args))
    "preview" (cmd-preview (rest args))
    "build" (cmd-build (rest args))
    (println (str "usage:\n"
                  "  noren.cljs discover [<area-id>] [--accept]   OSM + Common Crawl + murakumo で見込み事業者を発見\n"
                  "  noren.cljs tick [--submit]                   1 周（既定は dry-run）\n"
                  "  noren.cljs diagnose <url> [--catalog]\n"
                  "  noren.cljs preview <prospect-id>\n"
                  "  noren.cljs build <brief.edn> [--out f]\n"
                  "  noren.cljs verify-corpus                     receipt と raw の実体を突き合わせる\n"
                  "  noren.cljs llm-probe                         murakumo-main の解決と疎通だけ見る"))))

;; nbb は script への引数を `*command-line-args*` に入れる（process.argv を
;; 直接切ると nbb 自身の引数を数え間違える）。
(-main (vec *command-line-args*))
