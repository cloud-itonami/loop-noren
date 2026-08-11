(ns loop-noren.loop-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [loop-noren.loop :as l]
            [loop-noren.letter :as letter]
            [loop-noren.registry :as registry]
            [noren.prescribe :as prescribe]
            [noren.diagnose :as diagnose]
            [noren.governor :as gov]))

(def now "2026-08-11")
(def sender {:name "cloud-itonami" :address "東京都…" :opt-out-contact "stop@itonami.cloud"})

(def poor-page
  "<!DOCTYPE html><html><head><title>まる</title></head>
   <body><h1>そば処 まる</h1><p>おいしいそば。</p><button>予約</button></body></html>")

(def good-page
  ;; presence も UI も満たす面（自分たちが建てたものに近い形）
  (str "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
       "<meta name=\"theme-color\" content=\"#fff\"><meta property=\"og:title\" content=\"まる\">"
       "<meta property=\"og:description\" content=\"そば\"><meta property=\"og:url\" content=\"https://x.test/\">"
       "<script type=\"application/ld+json\">{\"@type\":\"Restaurant\",\"name\":\"まる\"}</script>"
       "<style>body{min-height:100dvh;overflow-x:clip;padding:env(safe-area-inset-left) env(safe-area-inset-right);"
       "color-scheme:light}button,a{min-height:48px}input{font-size:16px}"
       ":focus-visible{outline:2px solid}@media (prefers-reduced-motion: reduce){*{transition:none}}"
       "@media (max-width: 40rem){body{padding:0}}</style></head>"
       "<body><h1>そば処 まる</h1><address>東京都千代田区1-1-1</address>"
       "<p>営業時間 11:00〜15:00</p><p>2026年 冬のメニュー</p>"
       "<ul><li>せいろ 900円</li><li>天せいろ 1600円</li><li>鴨南蛮 1400円</li></ul>"
       "<a href=\"tel:0312345678\">03-1234-5678</a><a href=\"mailto:a@x.test\">mail</a>"
       "<form><input name=\"q\"><button>送信</button></form></body></html>"))

(def maru
  {:prospect/id "maru" :prospect/name "そば処 まる" :prospect/isic "5610"
   :prospect/kind :organization :prospect/source :manual
   :prospect/site-url "https://maru.test/" :prospect/observed-at "2026-08-10"
   :prospect/contact {:contact/email "i@maru.test"
                      :contact/source-url "https://maru.test/contact"
                      :contact/observed-at "2026-08-10"}})

(defn io-with
  ([pages] (io-with pages {}))
  ([pages over]
   (merge {:fetch (fn [url] (get pages url {:html nil :status 404}))
           :history (constantly [])
           :suppression (constantly #{})}
          over)))

(deftest tick-proposes-for-a-poor-site
  (let [sent (atom [])
        {:keys [results proposed]}
        (l/tick {:prospects [maru] :now now :year 2026 :sender sender
                 :io (io-with {"https://maru.test/" {:html poor-page :status 200}}
                              {:propose! (fn [r] (swap! sent conj r) {:queued true})})})
        r (first results)]
    (is (= 1 proposed))
    (is (= :proposed (:outcome r)))
    (is (= :rebuild (:verdict r)))
    (is (= 1 (count @sent)))
    (testing "本文には測った claim と法定表示が入る"
      (let [body (get-in r [:message :body])]
        (is (str/includes? body "stop@itonami.cloud"))
        (is (str/includes? body (:address sender)))
        (is (str/includes? body "自動検査で確認できた事実のみ"))))))

(deftest tick-does-not-touch-a-healthy-site
  (let [{:keys [results proposed]}
        (l/tick {:prospects [maru] :now now :year 2026 :sender sender
                 :io (io-with {"https://maru.test/" {:html good-page :status 200}})})
        r (first results)]
    (is (zero? proposed))
    (is (= :hold (:outcome r)))
    (is (= [:nothing-to-sell :no-measured-claim] (:violations r))
        "健全なサイトに用は無い —— ここが :proposed になる loop は営業ではなく迷惑")))

(deftest fetched-page-can-revoke-contactability
  (testing "登録時に見た値を信じ続けない。断りは後から書かれる"
    (let [page (str poor-page "<footer>営業メールはお断りしております</footer>")
          {:keys [results]} (l/tick {:prospects [maru] :now now :year 2026 :sender sender
                                     :io (io-with {"https://maru.test/" {:html page :status 200}})})]
      (is (= :hold (:outcome (first results))))
      (is (= [:prospect-ineligible] (:violations (first results)))))))

(deftest definitive-404-is-absent-not-zero
  (let [{:keys [results]} (l/tick {:prospects [maru] :now now :year 2026 :sender sender
                                   :io (io-with {"https://maru.test/" {:html nil :status 404}})})
        r (first results)]
    (is (= :absent (:verdict r)))
    (is (= :rebuild (:action r)) "面が無いなら建てる話になる")
    (is (= :proposed (:outcome r)))))

(deftest a-failed-fetch-is-not-a-missing-site
  (testing "接続エラーは**こちらの観測の失敗**。相手について何も言わない"
    (let [{:keys [results proposed]}
          (l/tick {:prospects [maru] :now now :year 2026 :sender sender
                   :io (io-with {"https://maru.test/" {:html nil :status "error: fetch failed"}})})
          r (first results)]
      (is (zero? proposed))
      (is (= :skip (:outcome r)))
      (is (= [:fetch-failed] (:reasons r)))))

  (testing "loop を迂回しても governor が同じところで止める"
    (let [d (diagnose/diagnose nil {:surface :storefront :url "https://maru.test/"
                                    :year 2026 :status 503})
          rx (prescribe/prescribe d)
          msg (letter/compose {:prospect maru :prescription rx :sender sender})
          r (gov/review {:prospect maru :prescription rx :message msg
                         :history [] :suppression #{} :now now})]
      (is (= :hold (:decision r)))
      (is (contains? (set (map :rule (:violations r))) :unverified-absence)))))

(deftest tick-stops-at-the-per-tick-cap
  (let [ps (mapv (fn [i] (assoc maru
                                :prospect/id (str "p" i)
                                :prospect/site-url (str "https://p" i ".test/")))
                 (range 5))
        pages (into {} (map (fn [p] [(:prospect/site-url p) {:html poor-page :status 200}]) ps))
        {:keys [proposed examined]} (l/tick {:prospects ps :now now :year 2026 :sender sender
                                             :io (io-with pages)})]
    (is (= l/max-per-tick proposed))
    (is (= l/max-per-tick examined) "上限に達したらそこで止める（残りは次の周）")))

(deftest ineligible-prospects-are-skipped-with-reasons
  (let [{:keys [results]} (l/tick {:prospects [(assoc maru :prospect/kind :individual)]
                                   :now now :year 2026 :sender sender :io (io-with {})})]
    (is (= :skip (:outcome (first results))))
    (is (= :observe (:stage (first results))))
    (is (contains? (set (:reasons (first results))) :recipient-not-a-business))))

;; ── registry ─────────────────────────────────────────────────────────────

(deftest registry-rejects-without-swallowing
  (let [{:keys [ok rejected]}
        (registry/validate [maru
                            (dissoc maru :prospect/source)
                            (assoc maru :prospect/id "bad" :prospect/site-url "")]
                           now)]
    (is (= 1 (count ok)))
    (is (= 2 (count rejected)))
    (is (contains? (set (:problems (first (filter #(= "maru" (:prospect %)) rejected))))
                   :unknown-source))))

(deftest registry-dedups-by-host
  (is (= 1 (count (registry/dedup [maru (assoc maru :prospect/id "dup"
                                               :prospect/site-url "http://www.maru.test/x")])))))

;; ── letter ───────────────────────────────────────────────────────────────

(deftest letter-carries-no-claim-of-its-own
  (let [rx (prescribe/prescribe (diagnose/diagnose poor-page
                                                   {:surface :storefront :url "https://maru.test/" :year 2026}))
        {:keys [body]} (letter/compose {:prospect maru :prescription rx :sender sender})]
    (testing "本文の各 claim 行は prescription の claim と 1:1"
      (doseq [c (:claims rx)]
        (is (str/includes? body (:claim/text c)))))
    (testing "貰っていない事実は「ご提供ください」であって、こちらの記載ではない"
      (is (str/includes? body "推測して記載することはしません")))))
