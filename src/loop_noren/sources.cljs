(ns loop-noren.sources
  "発見の I/O アダプタ。**nbb のみ**（`.cljs`）。

  3 つとも既存 repo の上に乗っている —— 新しく書いたのは繋ぎだけ:

  | 何 | どこ |
  |---|---|
  | OSM Overpass | `kotoba-lang/org-openstreetmap-overpass`（QL 組み立て・応答正規化・礼儀正しい間隔） |
  | Common Crawl | `net-kotobase/commoncrawl-actor` の `commoncrawl.cdx` / `live-http`（CDX 照会 + WARC range fetch + envelope 剥がし） |
  | LLM | `loop-noren.murakumo`（`murakumo-main` alias 解決） |

  ## 本文をまず Common Crawl から取る理由

  発見の段階では、まだその店に用があると決まっていない。**用があると決まる前に
  相手のサーバを叩かない**のが筋なので、既に公開クロールに載っている本文が
  あるならそれを読む。Common Crawl に無いときだけ live を 1 回だけ取る
  （その 1 回も、結局 evaluate 段で取ることになる面である）。

  取れた本文がどちらの経路か（`:common-crawl` / `:live`）は evidence に残す
  —— 古い capture を今日の本文として扱ってしまう誤りは、出所が見えれば気付ける。"
  (:require [clojure.string :as str]
            [commoncrawl.cdx :as cdx]
            [commoncrawl.extract :as extract]
            [commoncrawl.live-http :as cc-http]
            [loop-noren.discover :as discover]
            [noren.discovery :as discovery]
            [org-openstreetmap-overpass.core :as overpass]
            [org-openstreetmap-overpass.fetch :as overpass-fetch]))

(def ^:private cp (js/require "child_process"))

(def user-agent
  "識別可能な User-Agent。共有インフラ（Overpass / Common Crawl）にも相手の
  サーバにも、誰が叩いているかを名乗る。"
  "loop-noren/0.2 (+https://itonami.cloud/cloud-itonami/loop-noren)")

;; ── OSM ──────────────────────────────────────────────────────────────────

(defn fetch-candidates
  "bbox → `{:candidates [...] :without-website n :unclassified n :raw-count n}` の Promise。

  分類器は `noren.discovery/osm-tags->isic`（表。導出しない）を渡す ——
  **どの選択子で引かれたかではなくタグそのものから決める。**"
  [bbox]
  (-> (overpass-fetch/fetch-features
       bbox
       {:selectors (discovery/osm-selectors)
        :user-agent user-agent
        :parse-opts {:classify discovery/osm-tags->isic :attr :obs/isic}})
      (.then (fn [{:keys [observations unclassified raw-count]}]
               (merge (discover/observations->candidates observations)
                      {:unclassified unclassified :raw-count raw-count})))))

(defn ql-preview
  "実際に投げる QL。**投げる前に読めるようにしておく** —— 共有インフラへの
  クエリを目で確認できないまま回さない。"
  [bbox]
  (overpass/ql bbox {:selectors (discovery/osm-selectors)}))

;; ── 本文 ─────────────────────────────────────────────────────────────────

(defn ^:private live-html
  "live を 1 回だけ、同期で取る（curl）。→ `{:html :status}`。

  **status を必ず持ち帰る。** `-sSL` だけだと 404 ページの HTML が普通の本文
  として返り、`noren.discovery/usable-page-text?` に判断材料が渡らない
  （実測 2026-08-11: OSM の website タグが古く 404 の店を、404 ページの内容で
  読んでいた）。"
  [url]
  (let [out (try
              (-> (.execFileSync cp "curl"
                                 #js ["-sSL" "--max-time" "25" "-A" user-agent
                                      "-w" "\n%{http_code}" url]
                                 #js {:maxBuffer (* 16 1024 1024) :timeout 30000})
                  (.toString "utf8"))
              (catch :default e (some-> (.-stdout e) (.toString "utf8"))))]
    (if-not out
      {:html nil :status nil}
      (let [i (.lastIndexOf out "\n")]
        (if (neg? i)
          {:html out :status nil}
          (let [st (js/parseInt (subs out (inc i)) 10)]
            {:html (subs out 0 i) :status (when-not (js/isNaN st) st)}))))))

(defn page-text-fn
  "→ `(fn [url] {:text :source})`。`loop-noren.discover` の `:page-text` 契約。

  collection id は起動時に 1 回だけ解決する（Common Crawl は月次で増え、
  固定値を焼くと静かに古いクロールだけを見続ける）。解決できなければ
  Common Crawl 経路は丸ごと諦めて live に落ちる —— **推測した collection id で
  照会しない。**"
  []
  (let [collection (cdx/latest-collection-id cc-http/collections-fn)]
    (fn [url]
      (let [cc (when collection
                 (try (cdx/fetch-page-text cc-http/cdx-http-fn cc-http/warc-fetch-fn collection url)
                      (catch :default _ nil)))
            cc-text (some-> cc :text extract/strip-html not-empty)]
        (if cc-text
          {:text cc-text :source {:kind :common-crawl :collection collection
                                  :capture (select-keys (:capture cc) [:timestamp :filename])}}
          (let [{:keys [html status]} (live-html url)
                text (some-> html extract/strip-html not-empty)]
            (if (discovery/usable-page-text? status text)
              {:text text :source {:kind :live :status status}}
              {:text nil :source {:kind :none :status status}})))))))
