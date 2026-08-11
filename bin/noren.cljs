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
  "append-only の日次シャード（`kouhou` の corpus receipt と同型）。
  1 行 1 EDN。**書き換えない。**"
  [entry]
  (let [dir (at "journal")]
    (fs/mkdirSync dir #js {:recursive true})
    (.appendFileSync fs (path/join dir (str today ".edn"))
                     (str (pr-str (-> entry
                                      (dissoc :message :prescription)
                                      (assoc :at now-iso)))
                          "\n"))))

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
  "承認キューへ effect を積む。**送信ではない** —— 実行は cloud-itonami 側の
  承認を通る（`:effect/requires-approval true`）。"
  [result]
  (let [org (or js/process.env.NOREN_TENANT_ORG "cloud-itonami")
        repo (or js/process.env.NOREN_TENANT_REPO "loop-noren")
        token js/process.env.ITONAMI_OPERATOR_TOKEN]
    (if-not token
      (do (println "  ! ITONAMI_OPERATOR_TOKEN が無いので effect を積めない（提案は journal にだけ残る）")
          {:queued false :reason :no-token})
      (-> (js/fetch (str "https://itonami.cloud/api/" org "/" repo "/effects")
                    #js {:method "POST"
                         :headers #js {"content-type" "application/json"
                                       "authorization" (str "Bearer " token)}
                         :body (js/JSON.stringify
                                (clj->js {:effect/type "noren.outreach/proposal"
                                          :effect/risk "outbound-contact"
                                          :effect/requires-approval true
                                          :effect/payload {:prospect (:prospect result)
                                                           :receipt (:receipt result)
                                                           :subject (get-in result [:message :subject])
                                                           :body (get-in result [:message :body])}}))})
          (p/then (fn [r] {:queued (.-ok r) :status (.-status r)}))
          (p/catch (fn [e] {:queued false :reason (.-message e)}))))))

;; ── commands ─────────────────────────────────────────────────────────────

(defn- prospects []
  (let [{:keys [ok rejected]} (registry/validate (read-edn (at "data" "prospects.edn")) today)]
    (doseq [r rejected] (println "  ! 登録不備:" (:prospect r) (str/join "," (map name (:problems r)))))
    (registry/dedup ok)))

(defn- run-tick [ps {:keys [submit? record?]}]
  (p/let [pages (prefetch (map :prospect/site-url ps))]
    (l/tick {:prospects ps :now today :year year :sender sender
             :io (cond-> {:fetch (fn [url] (get pages url {:html nil :status "not-fetched"}))
                          :history history-for
                          :suppression #(set (or (read-edn (at "data" "suppression.edn")) #{}))}
                   record? (assoc :record! journal-append!)
                   submit? (assoc :propose! propose!))})))

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

(defn -main [args]
  (case (first args)
    "tick" (cmd-tick (rest args))
    "diagnose" (cmd-diagnose (rest args))
    "preview" (cmd-preview (rest args))
    "build" (cmd-build (rest args))
    (println "usage: noren.cljs tick [--submit] | diagnose <url> [--catalog] | preview <id> | build <brief.edn> [--out f]")))

;; nbb は script への引数を `*command-line-args*` に入れる（process.argv を
;; 直接切ると nbb 自身の引数を数え間違える）。
(-main (vec *command-line-args*))
