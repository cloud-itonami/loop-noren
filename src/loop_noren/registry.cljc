(ns loop-noren.registry
  "`data/prospects.edn` の検査。純 `.cljc`。

  **prospect は宣言する。名前や URL から導出しない。** ホスト名が
  それらしいからといって飲食店とは限らず、導出すると『ISIC 5610 だと
  思ったので送りました』が起きる。`sites.edn` の
  locations-are-declared-not-derived と同じ規律。

  観測の出所（`:prospect/source`）も必須にしてある。後で「この 1 件は
  どこから来たのか」を answer できない登録は、断られたときに直せない。"
  (:require [clojure.string :as str]
            [noren.prospect :as prospect]))

(def known-sources
  "prospect の出所として認めている経路。ここに無い値は弾く。

  `:osm-llm` は OSM のタグ（業種）+ Common Crawl / live の本文 + murakumo-main の
  抽出を、`noren.discovery` の照合と DiscoveryGovernor に通して作られた登録。
  `:passive-dns` はまだ配線されていない —— **在ることにしない**ために、
  `data/sources.edn` 側で `:enabled? false` と書いてある。"
  #{:manual :owner-inbound :osm-llm :common-crawl :passive-dns})

(defn validate
  "`[prospect …]` → `{:ok [..] :rejected [{:prospect :problems}]}`。
  1 件の不備で全体を落とさない（残りは進める）が、**黙って捨てない**。"
  [prospects now]
  (reduce
   (fn [acc p]
     (let [problems
           (cond-> []
             (str/blank? (:prospect/id p))        (conj :missing-id)
             (str/blank? (:prospect/name p))      (conj :missing-name)
             (str/blank? (:prospect/site-url p))  (conj :missing-site-url)
             (nil? (prospect/dedup-key p))        (conj :unparseable-site-url)
             (not (contains? known-sources (:prospect/source p))) (conj :unknown-source)
             (nil? (prospect/epoch-day (:prospect/observed-at p))) (conj :unparseable-observed-at))
           ;; 適格性は「今日は送れない」だけで登録の不備ではないので、
           ;; ここでは弾かない（loop の observe 段が理由つきで skip する）。
           _ (prospect/eligible p now)]
       (if (seq problems)
         (update acc :rejected conj {:prospect (:prospect/id p) :problems problems})
         (update acc :ok conj p))))
   {:ok [] :rejected []}
   prospects))

(defn dedup
  "同じ host の重複を落とす（先勝ち）。"
  [prospects]
  (->> prospects
       (reduce (fn [[seen out] p]
                 (let [k (prospect/dedup-key p)]
                   (if (contains? seen k) [seen out] [(conj seen k) (conj out p)])))
               [#{} []])
       second))
