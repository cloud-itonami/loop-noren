(ns loop-noren.loop
  "observe → evaluate → decide → act → record-evidence。純 `.cljc`。

  I/O は**全部引数で受け取る**（`io` map の関数）。そうしてある理由は 2 つ:

  1. loop 全体がテストで回る。fetch を固定した HTML に差し替えれば、
     実ネットワーク無しで「この店にはこう判断する」が確かめられる
  2. **どこで外に出るかが 1 画面に収まる。** 送信の分岐が散っていると、
     `--dry-run` を付けたのに送っていた、が起きる

  判定は 1 つも持たない —— 適格性も採点も処方も送信可否も `noren` にある。
  ここが持つのは順序と、1 周で何件やるかだけ（`manifest/repository-rules.edn`
  の `loop-*` は `:must-not [:own-domain-scoring-truth]`）。"
  (:require [clojure.string :as str]
            [noren.prospect :as prospect]
            [noren.diagnose :as diagnose]
            [noren.prescribe :as prescribe]
            [noren.governor :as gov]
            [loop-noren.letter :as letter]))

(def max-per-tick
  "1 周で接触を提案する上限。`kaiyu-kaizen` と同じ理屈 —— 5 件積むとどれも
  読まれず、queue を読まれなくすることが測定を全部無駄にする唯一の方法。"
  1)

(defn ^:private decide
  "取得済みの面について、処方 → 文面 → governor まで進める。"
  [{:keys [io now sender year]} p key elig html status]
  (let [;; 取得した HTML から受信拒否表明を読み直す。登録時に見た値を
        ;; 信じ続けない —— 断りは後から書かれる。
        p (cond-> p (prospect/declared-opt-out? html)
                  (assoc :prospect/opt-out-declared? true))
        d (diagnose/diagnose html {:surface (:surface elig)
                                   :url (:prospect/site-url p)
                                   :year year
                                   :status status})
        rx (prescribe/prescribe d)
        msg (letter/compose {:prospect p :prescription rx :sender sender})
        review (gov/review {:prospect p :prescription rx :message msg
                            :history ((:history io) key)
                            :suppression ((:suppression io))
                            :now now})]
    (cond-> {:prospect key
             :stage :decide
             :http-status status
             :verdict (:verdict d)
             :overall (:overall d)
             :action (:action rx)
             :decision (:decision review)
             :violations (mapv :rule (:violations review))
             :warnings (mapv :rule (:warnings review))
             :receipt (:receipt review)}
      (= :commit (:decision review)) (assoc :outcome :proposed :message msg :prescription rx)
      (= :hold (:decision review))   (assoc :outcome :hold))))

(defn ^:private step
  "1 事業者を 1 周ぶん進める。返るのは常に**記録できる形**（skip も理由つき）。"
  [{:keys [io now] :as ctx} p]
  (let [key (prospect/dedup-key p)
        elig (prospect/eligible p now)]
    (if-not (:ok? elig)
      {:prospect key :outcome :skip :stage :observe
       :reasons (mapv :rule (:reasons elig))}
      (let [{:keys [html status]} ((:fetch io) (:prospect/site-url p))]
        (if (and (str/blank? html) (not (diagnose/definitive-absence? status)))
          ;; 取得に失敗しただけ。**相手について何も分かっていない**ので次の周へ回す。
          ;; governor も `:unverified-absence` で止めるが、ここで止めれば
          ;; 1 周ぶんの枠を無駄にしない。
          {:prospect key :outcome :skip :stage :evaluate
           :http-status status :reasons [:fetch-failed]}
          (decide ctx p key elig html status))))))

(defn tick
  "1 周。`prospects` を順に見て、**提案できるものが `max-per-tick` 件たまったら
  そこで止める**（残りは次の周へ）。

  `io` = {:fetch (fn [url] {:html :status})
          :history (fn [key] [{:contacted-at \"…\"} …])
          :suppression (fn [] #{key …})
          :propose! (fn [result] …)   ; 承認キューへ。dry-run では省く
          :record! (fn [entry] …)}"
  [{:keys [prospects io] :as ctx}]
  (loop [[p & more] (vec prospects)
         results []
         proposed 0]
    (if (or (nil? p) (>= proposed max-per-tick))
      {:results results
       :proposed proposed
       :examined (count results)
       :remaining (count (cond->> (vec more) (some? p) (cons p)))}
      (let [r (step ctx p)
            r (if (and (= :proposed (:outcome r)) (:propose! io))
                (assoc r :queued ((:propose! io) r))
                r)]
        (when-let [record! (:record! io)] (record! r))
        (recur more (conj results r)
               (cond-> proposed (= :proposed (:outcome r)) inc))))))
