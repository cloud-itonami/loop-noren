(ns loop-noren.store-io
  "永続化の I/O。**nbb のみ**（`.cljs`）。形は `loop-noren.store`（純）が持つ。

  ここが触るのはファイルシステムだけ。annex への copy は `git annex` の仕事で、
  この ns はやらない —— **書くことと預けることを同じ関数にしない**（預け先が
  落ちているときに書き込みまで止まる)。"
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [loop-noren.canonical :as canonical]
            [loop-noren.store :as store]))

(defn sha256 [s]
  (-> (.createHash crypto "sha256") (.update s "utf8") (.digest "hex")))

(defn byte-length [s] (.byteLength (.-Buffer js/globalThis) s "utf8"))

(defn ^:private ensure-dir! [f]
  (fs/mkdirSync (path/dirname f) #js {:recursive true}))

(defn append-lines!
  "canonical EDN を追記する。**上書きしない。**"
  [root rel records]
  (when (seq records)
    (let [f (path/join root rel)]
      (ensure-dir! f)
      (.appendFileSync fs f (canonical/lines records))
      f)))

(defn read-lines
  "行区切り EDN を読む。壊れた行は**握り潰さず**数える。"
  [root rel]
  (let [f (path/join root rel)]
    (if-not (fs/existsSync f)
      {:records [] :unreadable 0}
      (reduce (fn [acc line]
                (if (str/blank? line)
                  acc
                  (try (update acc :records conj (edn/read-string line))
                       (catch :default _ (update acc :unreadable inc)))))
              {:records [] :unreadable 0}
              (str/split-lines (.readFileSync fs f "utf8"))))))

(defn write-raw!
  "本文/生返答を raw 面へ書き、receipt に載せる `{:path :sha256 :bytes}` を返す。
  content が空なら nil（**空ファイルを証跡として残さない**）。"
  [root {:keys [content kind ext at]}]
  (when-not (str/blank? content)
    (let [digest (sha256 content)
          rel (store/raw-path {:sha256 digest :kind kind :ext ext :at at})
          f (path/join root rel)]
      (ensure-dir! f)
      ;; 内容アドレスなので、既に在れば書き直さない（annex の pointer を無駄に
      ;; 触らない）。
      (when-not (fs/existsSync f) (.writeFileSync fs f content))
      {:path rel :sha256 digest :bytes (byte-length content)})))

(defn stat-fn
  "`store/verify` に渡す実測関数。annex から drop されていれば `:absent`
  （pointer ファイルはあるが実体が無い状態を、壊れたと数えない）。"
  [root]
  (fn [rel]
    (let [f (path/join root rel)]
      (cond
        (not (fs/existsSync f)) nil
        ;; annex の pointer は 1 行の `/annex/objects/...`。実体ではない。
        (let [s (.readFileSync fs f "utf8")]
          (and (< (count s) 512) (str/starts-with? (str/trim s) "/annex/")))
        :absent
        :else (let [s (.readFileSync fs f "utf8")]
                {:sha256 (sha256 s) :bytes (byte-length s)})))))

(defn all-receipts
  "`data/corpus/*.edn` 全部。"
  [root]
  (let [dir (path/join root store/corpus-dir)]
    (if-not (fs/existsSync dir)
      []
      (->> (fs/readdirSync dir)
           (filter #(str/ends-with? % ".edn"))
           sort
           (mapcat #(:records (read-lines root (str store/corpus-dir "/" %))))
           vec))))
