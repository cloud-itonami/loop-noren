(ns loop-noren.letter
  "提案 1 通の組み立て。**claim を増やさない組み立て**であることが全て。

  ここは presentation であって truth ではない —— 何を言えるかを決めるのは
  `noren.prescribe`、出してよいかを決めるのは `noren.governor`。この ns は
  受け取った claim を並べ、法定の表示（送信者・受信拒否）を必ず付ける。

  文面を LLM に書かせたくなったときも、差し替えてよいのはこの ns だけで、
  出力は governor を必ず通る。governor は claim の軸が evidence に無ければ
  止めるので、**LLM が新しい売り文句を足した瞬間に hold になる。**"
  (:require [clojure.string :as str]
            [noren.plan :as plan]))

(defn compose
  "`{:prospect :prescription :sender :now}` → `{:subject :body :sender}`。"
  [{:keys [prospect prescription sender]}]
  (let [shop (:prospect/name prospect)
        url  (:prospect/site-url prospect)
        plan-id (plan/recommend prescription)
        p (plan/plan plan-id)
        charge (plan/monthly-charge plan-id)
        claims (:claims prescription)]
    {:subject (str shop "さまの Web サイトを拝見しました（"
                   (count claims) "点の指摘）")
     :body
     (str/join
      "\n"
      (concat
       [(str shop " ご担当者さま")
        ""
        (str "公開されている " url " を機械的に検査したところ、"
             "次の点が見つかりました。いずれも自動検査で確認できた事実のみです。")
        ""]
       (map-indexed (fn [i c] (str (inc i) ". " (:claim/text c))) claims)
       [""
        (case (:action prescription)
          :rebuild "現在の構成のままでは個別の修正が積み重なるため、作り直しをご提案します。"
          :repair  "現在のサイトに手を入れる形で対応できます。"
          "")
        ""
        (when p
          (str "ご提案: " (:plan/label p) "プラン（" (:plan/for p) "）"
               " 月額 " (:net charge) " 円（税込 " (:gross charge) " 円）。"))
        (when (seq (:needs-owner-input prescription))
          (str "作成にあたっては、" (str/join "・" (keep {:fact/address "所在地"
                                                          :fact/tel "電話番号"
                                                          :fact/hours "営業時間"
                                                          :fact/menu-items "メニューと価格"
                                                          :fact/catalog-items "商品と価格"
                                                          :fact/commerce-terms "特定商取引法に基づく表記"}
                                                        (:needs-owner-input prescription)))
               "をご提供ください。こちらで推測して記載することはしません。"))
        ""
        "──"
        (str (:name sender))
        (str (:address sender))
        (str "本メールの配信停止・以後のご連絡不要のご連絡は " (:opt-out-contact sender) " へお願いします。")]))
     :sender sender}))
