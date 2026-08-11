(ns loop-noren.discover
  "発見の 1 周。observe(OSM) → read(本文) → extract(LLM) → verify → govern → 登録。
  純 `.cljc`。I/O は `io` map で受け取る（`loop-noren.loop` と同じ規律）。

  ## LLM がここに居てよい理由

  murakumo-main が読むのは相手のページ本文で、返してよいのは**本文にそのまま
  在る文字列**だけ。`noren.discovery/verify-extraction` が 1 つずつ照合し、
  verbatim で見つからないものは落とす。**モデルが幻覚しても、原文に無い文字列は
  1 つも通らない。** 業種はモデルではなく OSM のタグ（人が現地で付けた宣言）から
  決まる。

  したがってモデルを替えても、この loop が『何を根拠に売るか』は変わらない。
  変わるのは抽出の歩留まりだけで、それは `:dropped` の件数として見える。

  ## 1 周で 1 件しか受理しない理由は loop 側と違う

  `loop-noren.loop` の上限は「読まれない queue を作らない」ため。こちらの上限は
  **相手のサーバと Overpass と LLM への負荷**のため。だから既定値も別に持つ。"
  (:require [clojure.string :as str]
            [noren.discovery :as discovery]
            [noren.prospect :as prospect]))

(def max-accept-per-run
  "1 回の discover で名簿に加える上限。"
  5)

(def max-examine-per-run
  "1 回の discover で本文を読む上限（受理できなくても読んだ回数は増える）。
  LLM 呼び出し回数の天井でもある。"
  20)

(defn ^:private examine
  "candidate 1 件。`io` = {:page-text (fn [url] {:text :source}) :complete (fn [prompt system] reply)}"
  [{:keys [io now model]} candidate]
  (let [{:keys [text source]} ((:page-text io) (:candidate/site-url candidate))]
    (if (str/blank? text)
      {:candidate (:candidate/id candidate) :decision :reject
       :violations [{:rule :no-page-text
                     :detail "本文が取れなかった（Common Crawl にも無く、live でも取れない）"}]}
      (let [prompt (discovery/build-prompt {:text text
                                            :osm-name (:candidate/osm-name candidate)
                                            :site-url (:candidate/site-url candidate)})
            raw ((:complete io) prompt discovery/extraction-system-prompt)
            parsed (discovery/parse-extraction raw)]
        (cond
          ;; **モデルが答えなかったことを、相手についての判定にしない。**
          ;; 抽出できなかったのとモデルが落ちていたのは別物で、前者は却下、
          ;; 後者は次の周でやり直す対象である。
          (nil? raw)
          {:candidate (:candidate/id candidate) :decision :defer
           :violations [{:rule :llm-unavailable
                         :detail "モデルが応答しなかった。候補は却下せず次の周へ回す"}]}

          (:parse-error parsed)
          {:candidate (:candidate/id candidate) :decision :reject
           :violations [{:rule :unparseable-extraction :detail (str (:parse-error parsed))}]
           :raw-reply raw}

          :else
          (let [verification (discovery/verify-extraction parsed text)
                review (discovery/review-candidate candidate verification)]
            (cond-> {:candidate (:candidate/id candidate)
                     :decision (:decision review)
                     :violations (:violations review)
                     :dropped (mapv :field (:dropped verification))
                     :text-source source
                     :raw-reply raw}
              (= :accept (:decision review))
              (assoc :prospect (discovery/->prospect candidate verification
                                                    {:now now :text-source source
                                                     :model model :raw-reply raw})))))))))

(defn run
  "`{:candidates :io :now :model :known}` → `{:accepted :results :examined}`。

  `known` は既に名簿に在る dedup-key の集合。**発見のたびに同じ店を読み直さない**
  —— 相手のサーバに用も無く触るのは、この loop が最初にやってはいけないことの 1 つ。"
  [{:keys [candidates io now known] :as ctx}]
  (let [known (set (or known #{}))]
    (loop [[c & more] (vec candidates)
           results []
           accepted []
           examined 0]
      (if (or (nil? c)
              (>= (count accepted) max-accept-per-run)
              (>= examined max-examine-per-run))
        {:accepted accepted
         :results results
         :examined examined
         :remaining (count (cond->> (vec more) (some? c) (cons c)))}
        (if (contains? known (prospect/dedup-key {:prospect/site-url (:candidate/site-url c)}))
          ;; 既知。読まない（LLM も呼ばない）。
          (recur more (conj results {:candidate (:candidate/id c) :decision :skip
                                     :violations [{:rule :already-known}]})
                 accepted examined)
          (let [r (examine ctx c)]
            (when-let [record! (:record! io)] (record! r))
            (recur more (conj results r)
                   (cond-> accepted (:prospect r) (conj (:prospect r)))
                   (inc examined))))))))

(defn observations->candidates
  "Overpass の観測列 → candidate 列（website を持つ独立店だけ）。

  **落ちた理由を分けて数える** —— 『この地域に店が無い』『店は在るが website を
  持っていない』『チェーン店舗だった』は別の事実で、混ぜると営業先が無い理由を
  誤診する。実測 2026-08-11（神楽坂）: 387 element のうち website 持ちが 50、
  そのうちチェーンが 21 だった。"
  [observations]
  (let [with-site (filter #(let [t (:obs/tags %)]
                             (and (or (get t "website") (get t "contact:website"))
                                  (:obs/isic %)))
                          observations)
        cs (keep discovery/observation->candidate observations)]
    {:candidates (vec cs)
     :without-website (- (count observations) (count with-site))
     :chain-outlets (- (count with-site) (count cs))}))
