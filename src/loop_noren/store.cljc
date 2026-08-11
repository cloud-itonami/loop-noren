(ns loop-noren.store
  "永続化の**形**（純 `.cljc`）。書き込みそのものは `store.cljs`。

  ## 4 面に分ける。役割が違うものを同じ場所に置かない

  | 面 | パス | 中身 | bytes |
  |---|---|---|---|
  | 名簿 | `data/prospects.edn` | 見込み事業者の宣言 | git |
  | journal | `journal/<UTC日>.edn` | 決定事実（1 行 1 EDN、追記のみ） | git |
  | corpus receipt | `data/corpus/<UTC日>.edn` | 読み取り 1 回につき 1 件。`{:path :sha256 :bytes}` | git |
  | raw | `raw/<UTC日>/<sha7>-<kind>.<ext>` | 読んだ本文・生返答・診断した HTML そのもの | **git-annex → s3.kotobase.net** |

  `kouhou` が ADR-2608110200 で landed した形と同型。理由も同じ:

  **receipt が 2 面の join である。** 提案を見たら、それが**どの本文の**どの測定から
  出たのかを名指しでき、digest で照合できる。receipt が無ければ対応づけは
  ファイル名頼りになり、それは内容について何も証明しない。

  **raw を git に置かない。** 1 周で本文 20 件 + 生返答 20 件、毎日回せば年で
  数 GB になる。actor を clone するのに corpus のダウンロードが要る状態にしない
  （superproject の `large-binary-datalad` 規則）。annex remote は `kotobase`
  （`s3.kotobase.net`、bucket `cloud-itonami-loop-noren`）。

  **journal に生返答を書かない。** 以前は `:raw-reply` をそのまま journal に
  書いていたので、モデルの出力が決定ログに混ざって際限なく膨らんでいた。
  journal が持つのは **sha256 だけ**で、実体は raw 面に居る。

  ## 追記のみ、は「文書は最新状態のみ」に反しない

  あの規則は**文書**を縛り、測定とイベント列を明示的な例外にしている。
  過去の決定の列はまさにそれで、上書きすると後から引き直せなくなる。"
  (:require [clojure.string :as str]))

(def raw-dir "raw")
(def corpus-dir "data/corpus")
(def journal-dir "journal")

(defn day
  "ISO 時刻 → UTC の日付部分。シャードの単位。"
  [iso] (subs (str iso) 0 10))

(defn raw-path
  "`{:sha256 :kind :ext}` → raw 面のパス。**内容から決まる名前**にしてある ——
  同じ本文を 2 回読んでも 1 つのオブジェクトで、annex の重複排除がそのまま効く。"
  [{:keys [sha256 kind ext at]}]
  (str raw-dir "/" (day at) "/" (subs (str sha256) 0 12) "-" (name kind) "." (or ext "txt")))

(defn receipt
  "読み取り 1 回 → receipt 1 件。**読めなかったことも receipt にする** ——
  「読んで何も無かった」と「読めなかった」を後から区別できないと、
  歩留まりの議論ができない。"
  [{:keys [at subject kind url text-source status sha256 bytes path error]}]
  (cond-> {:noren/kind :corpus
           :noren/as-of at
           :subject subject
           :read-kind kind
           :url url
           :text-source (:kind text-source)
           :http-status status}
    sha256 (assoc :raw {:path path :sha256 sha256 :bytes bytes})
    error (assoc :error error)))

(defn journal-entry
  "loop / discover の結果 → journal 1 行。**raw は sha256 で参照する**。"
  [entry at]
  (-> entry
      (dissoc :message :prescription :raw-reply :prospect-record)
      (assoc :noren/kind :decision :at at)))

(defn corpus-shard [at] (str corpus-dir "/" (day at) ".edn"))
(defn journal-shard [at] (str journal-dir "/" (day at) ".edn"))

;; ── 検証 ─────────────────────────────────────────────────────────────────

(defn verify
  "receipt 列 + `(fn [path] {:sha256 :bytes} | :absent)` → 検証結果。

  **identity（この実体は receipt の言うものか）だけを見る。** annex から
  drop された bytes は `:absent` であって失敗ではない —— drop できることが
  annex を使う理由なので、それを失敗と数えると『容量を空けたら赤くなる』
  gate になる。**custody（object plane がまだ持っているか）は別の問い**で、
  `git annex find --in kotobase raw/` が答える（kouhou の実測: fsck の `ok` を
  custody の数と読むと 48 件のうち 10 件の欠落を見落とす）。"
  [receipts stat-fn]
  (reduce
   (fn [acc r]
     (if-let [{:keys [path sha256 bytes]} (:raw r)]
       (let [got (stat-fn path)]
         (cond
           (= :absent got) (update acc :absent conj path)
           (nil? got) (update acc :missing conj path)
           (and (= sha256 (:sha256 got)) (= bytes (:bytes got))) (update acc :ok inc)
           :else (update acc :mismatch conj {:path path
                                             :expected {:sha256 sha256 :bytes bytes}
                                             :actual (select-keys got [:sha256 :bytes])})))
       (update acc :no-raw inc)))
   {:ok 0 :no-raw 0 :absent [] :missing [] :mismatch []}
   receipts))

(defn verdict
  "検証結果 → `:ok` / `:broken`。**mismatch と missing だけが failure。**"
  [{:keys [missing mismatch]}]
  (if (and (empty? missing) (empty? mismatch)) :ok :broken))
