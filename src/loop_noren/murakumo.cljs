(ns loop-noren.murakumo
  "murakumo fleet の LLM を呼ぶ。**nbb のみ**（`.cljs`）。

  ## モデル名を焼かない（ADR-2607173100）

  fleet の main は KV の alias entry `murakumo-main` が指す先で、モデルの
  入れ替えはその 1 entry の PUT で済む。したがって解決順は:

    1. 環境変数の明示 override（`MURAKUMO_ENDPOINT` / `MURAKUMO_MODEL`）
    2. alias 解決（`GET /infer/models/murakumo-main` → `{endpoint, alias-for}`）
    3. endpoint だけの fallback —— **モデル名は焼かない。** endpoint 先が
       serving しているモデルに従う（`murakumo-main` を投げれば worker が
       KV で解決する）

  具体の model id（`qwen3.6-…` 等）をこのファイルに書いてはいけない。

  ## なぜ同期（curl）か

  `loop-noren.discover` は純関数で、注入される capability は**同期関数**である
  ことを要求する（`langgraph` の node と同じ理由）。`commoncrawl.live-http` が
  同じ判断で `child_process.execFileSync` + curl を使っているので、その idiom に
  揃えた。promise を loop の中に持ち込むと、どこで外に出るかが 1 画面に
  収まらなくなる。"
  (:require [clojure.string :as str]))

(def ^:private cp (js/require "child_process"))

(def alias-url "https://api.murakumo.cloud/infer/models/murakumo-main")
(def default-endpoint "https://api.murakumo.cloud/v1/messages")
(def default-model "murakumo-main")

(defn ^:private curl
  "argv → stdout 文字列 or nil。**例外を投げない**（transport の失敗は
  『分からなかった』であって、判定を止める理由にはするが例外にはしない）。"
  [args & [{:keys [input timeout-ms] :or {timeout-ms 120000}}]]
  (try
    (-> (.execFileSync cp "curl" (clj->js args)
                       (clj->js (cond-> {:maxBuffer (* 32 1024 1024) :timeout timeout-ms}
                                  input (assoc :input input))))
        (.toString "utf8"))
    (catch :default e (some-> (.-stdout e) (.toString "utf8")))))

(defn ^:private json-parse [s]
  (try (js->clj (js/JSON.parse s) :keywordize-keys true) (catch :default _ nil)))

(defn resolve-target
  "→ `{:endpoint :model :via}`。`:via` は解決経路（`:env` / `:alias` /
  `:fallback`）で、evidence に残すために返す —— どのモデル面に聞いたのかが
  後から分からない抽出は、引き直せない。"
  []
  (let [env-ep (some-> js/process.env.MURAKUMO_ENDPOINT not-empty)
        env-model (some-> js/process.env.MURAKUMO_MODEL not-empty)]
    (if (and env-ep env-model)
      {:endpoint env-ep :model env-model :via :env}
      (let [j (some-> (curl ["-sS" "--max-time" "15" alias-url] {:timeout-ms 20000}) json-parse)]
        (if-let [ep (or env-ep (:endpoint j))]
          {:endpoint ep
           :model (or env-model default-model)
           ;; alias-for は記録するが**使わない**。使うと具体 model id を
           ;; 焼くのと同じことになり、切替に追従しなくなる。
           :alias-for (:alias-for j)
           :via (if env-ep :env :alias)}
          {:endpoint default-endpoint :model (or env-model default-model) :via :fallback})))))

(defn ^:private reply-text
  "Anthropic 形（`content[].text`）と OpenAI 形（`choices[0].message.content`）の
  両方を読む。どちらでもなければ nil（空文字にしない —— 空返答と読めない返答は
  別物で、後者は evidence に残す価値がある）。"
  [j]
  (or (some->> (:content j) (filter #(= "text" (:type %))) (map :text) (apply str) not-empty)
      (get-in j [:choices 0 :message :content])
      (:completion j)))

(defn request-body
  "endpoint の**形**からリクエストの形を決める。model 名からは決めない。

  実測 2026-08-11: `murakumo-main` の alias は
  `https://infer.murakumo.cloud/v1/chat/completions`（OpenAI 形、認証不要）を
  指していた。`https://api.murakumo.cloud/v1/messages`（Anthropic 形）は
  bearer token を要求する。**どちらを指すかは alias の持ち物**なので、
  ここは経路の形だけを見て body を組む。"
  [endpoint model prompt system]
  (if (str/includes? endpoint "/chat/completions")
    {:model model :max_tokens 900 :stream false
     :messages (cond-> []
                 (not (str/blank? system)) (conj {:role "system" :content system})
                 true (conj {:role "user" :content prompt}))}
    (cond-> {:model model :max_tokens 900 :stream false
             :messages [{:role "user" :content prompt}]}
      (not (str/blank? system)) (assoc :system system))))

(def last-error
  "直近の transport / API エラー。**握り潰さないために持つ** —— nil が返った
  理由が『モデルが読み込み中』なのか『認証が無い』なのかは、運用が知る必要がある。"
  (atom nil))

(def ^:private retryable-pause-ms 20000)

(def ^:private consecutive-failures (atom 0))

(def circuit-open-after
  "この回数続けて応答が無ければ、以降は**呼ばずに nil を返す**。

  実測 2026-08-11: fleet の main が `Loading model`(503) の間に discover を
  回したところ、候補 1 件ごとに 20 秒待って再試行していたため、20 件の run が
  10 分以上かかっていた。モデルが落ちているときに一番やってはいけないのは
  『全件に同じ待ちを払う』こと —— 1 件目で分かったことを 20 回確かめている。"
  2)

(defn circuit-open? [] (>= @consecutive-failures circuit-open-after))

(defn complete-fn
  "→ `(fn [prompt system] -> reply-string | nil)`。
  `loop-noren.discover` の `:complete` 契約。

  503（`Loading model`）は 1 度だけ待って再試行する。それ以上は諦めて nil を
  返す —— **待ち続ける発見 loop は、止まっていることに気付けない。**"
  ([] (complete-fn (resolve-target)))
  ([{:keys [endpoint model]}]
   (let [token (some-> js/process.env.MURAKUMO_TOKEN not-empty)
         call (fn [prompt system]
                (let [body (js/JSON.stringify (clj->js (request-body endpoint model prompt system)))]
                  (curl (cond-> ["-sS" "--max-time" "110" "-X" "POST" endpoint
                                 "-H" "content-type: application/json"]
                          token (into ["-H" (str "authorization: Bearer " token)])
                          true (into ["--data-binary" "@-"]))
                        {:input body})))]
     (fn [prompt system]
       (if (circuit-open?)
         nil                              ; もう聞かない。呼び出し側は :defer にする
         (let [out (call prompt system)
               j (some-> out json-parse)
               txt (some-> j reply-text)]
           (cond
             txt
             (do (reset! last-error nil) (reset! consecutive-failures 0) txt)

             (some-> j :error :message (str/includes? "Loading model"))
             (do (reset! last-error (get-in j [:error :message]))
                 (swap! consecutive-failures inc)
                 ;; 1 度だけ待つ。execFileSync と同じ同期の世界に居るので sleep も同期。
                 (try (.execFileSync cp "sleep" #js [(str (quot retryable-pause-ms 1000))])
                      (catch :default _ nil))
                 (let [out2 (call prompt system)
                       t2 (some-> out2 json-parse reply-text)]
                   (if t2
                     (do (reset! last-error nil) (reset! consecutive-failures 0) t2)
                     (do (reset! last-error
                                 (str "retry も不可: "
                                      (some-> out2 (subs 0 (min 200 (count out2))))))
                         nil))))

             :else
             (do (reset! last-error (or (get-in j [:error :message])
                                        (some-> out (subs 0 (min 200 (count out))))
                                        "no response"))
                 (swap! consecutive-failures inc)
                 nil))))))))
