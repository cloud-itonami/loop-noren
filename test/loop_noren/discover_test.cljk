(ns loop-noren.discover-test
  (:require [clojure.test :refer [deftest is testing]]
            [loop-noren.discover :as discover]
            [noren.discovery :as d]))

(def now "2026-08-11")

(def text
  "そば処 まる｜手打ちそば
   営業時間 11:00〜15:00
   東京都新宿区神楽坂1-1-1
   ご予約は info@soba-maru.example.jp までお願いします。")

(def candidate
  {:candidate/id "node/1234"
   :candidate/osm-name "そば処 まる"
   :candidate/isic "5610"
   :candidate/site-url "https://soba-maru.example.jp/"
   :candidate/osm-url "https://www.openstreetmap.org/node/1234"})

(def good-reply
  (pr-str {:business-name "そば処 まる"
           :email "info@soba-maru.example.jp"
           :email-context "ご予約は info@soba-maru.example.jp までお願いします。"
           :opt-out-text nil :kind :organization :own-site? true
           :closed? false :confidence 0.9}))

(defn io-with [{:keys [reply calls pages]}]
  {:page-text (fn [url] (get pages url {:text text :source {:kind :common-crawl}}))
   :complete (fn [prompt system]
               (when calls (swap! calls conj {:prompt prompt :system system}))
               (if (fn? reply) (reply prompt) reply))})

(deftest a-verified-candidate-becomes-a-prospect
  (let [calls (atom [])
        {:keys [accepted results examined]}
        (discover/run {:candidates [candidate] :now now :model "murakumo-main"
                       :io (io-with {:reply good-reply :calls calls})})]
    (is (= 1 examined))
    (is (= :accept (:decision (first results))))
    (is (= 1 (count accepted)))
    (is (= :osm-llm (:prospect/source (first accepted))))
    (testing "モデルには抽出の指示と本文だけを渡す（判定を頼まない）"
      (is (= d/extraction-system-prompt (:system (first @calls))))
      (is (re-find #"ページ本文ここから" (:prompt (first @calls)))))))

(deftest a-hallucinated_extraction_is_rejected
  (testing "原文に無いアドレスを返してきたら受理しない"
    (let [reply (pr-str {:business-name "そば処 まる"
                         :email "sales@totally-different.example.com"
                         :email-context "お問い合わせはこちら"
                         :kind :organization :own-site? true :confidence 0.95})
          {:keys [accepted results]}
          (discover/run {:candidates [candidate] :now now :io (io-with {:reply reply})})]
      (is (empty? accepted))
      (is (= :reject (:decision (first results))))
      (is (= #{:email :email-context} (set (:dropped (first results))))))))

(deftest a-silent-model-defers-the-candidate
  (testing "モデルが答えなかったことを、相手についての判定にしない"
    (let [{:keys [accepted results]}
          (discover/run {:candidates [candidate] :now now :io (io-with {:reply nil})})]
      (is (empty? accepted))
      (is (= :defer (:decision (first results))))
      (is (= [:llm-unavailable] (map :rule (:violations (first results))))))))

(deftest unparseable-reply-is-rejected-but-kept
  (let [{:keys [results]} (discover/run {:candidates [candidate] :now now
                                         :io (io-with {:reply "すみません、分かりません"})})
        r (first results)]
    (is (= :reject (:decision r)))
    (is (= [:unparseable-extraction] (map :rule (:violations r))))
    (is (= "すみません、分かりません" (:raw-reply r)) "生返答は残す")))

(deftest known-hosts-are-not-read-at-all
  (testing "既知の相手には LLM も呼ばず、本文も取りに行かない"
    (let [calls (atom [])
          fetched (atom [])
          io (assoc (io-with {:reply good-reply :calls calls})
                    :page-text (fn [url] (swap! fetched conj url) {:text text :source {:kind :live}}))
          {:keys [results examined]}
          (discover/run {:candidates [candidate] :now now :io io
                         :known #{"soba-maru.example.jp"}})]
      (is (zero? examined))
      (is (= :skip (:decision (first results))))
      (is (empty? @calls))
      (is (empty? @fetched)))))

(deftest no-page-text-is-not-a-judgement-about-the-shop
  (let [{:keys [results]} (discover/run {:candidates [candidate] :now now
                                         :io (io-with {:reply good-reply
                                                       :pages {"https://soba-maru.example.jp/"
                                                               {:text nil :source {:kind :none}}}})})]
    (is (= :reject (:decision (first results))))
    (is (= [:no-page-text] (map :rule (:violations (first results)))))))

(deftest caps-bound-the-run
  (let [cs (mapv (fn [i] (assoc candidate
                                :candidate/id (str "node/" i)
                                :candidate/site-url (str "https://s" i ".example.jp/")))
                 (range 40))
        calls (atom [])
        {:keys [accepted examined]}
        (discover/run {:candidates cs :now now :io (io-with {:reply good-reply :calls calls})})]
    (is (= discover/max-accept-per-run (count accepted)))
    (is (<= examined discover/max-examine-per-run))
    (is (= examined (count @calls)) "LLM 呼び出しは examined と 1:1（上限がそのまま費用の上限）")))

(deftest observations-without-a-website-are-counted-not-hidden
  (let [obs [{:obs/source-id "node/1" :obs/isic "5610"
              :obs/tags {"amenity" "restaurant" "website" "https://a.example.jp/"}
              :obs/evidence-url "https://www.openstreetmap.org/node/1"}
             {:obs/source-id "node/2" :obs/isic "5610"
              :obs/tags {"amenity" "restaurant"}
              :obs/evidence-url "https://www.openstreetmap.org/node/2"}]
        {:keys [candidates without-website]} (discover/observations->candidates obs)]
    (is (= 1 (count candidates)))
    (is (= 1 without-website)
        "『店が無い』と『店が website を持っていない』は別の事実")))
