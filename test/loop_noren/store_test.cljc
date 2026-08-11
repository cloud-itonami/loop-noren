(ns loop-noren.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [loop-noren.canonical :as canonical]
            [loop-noren.store :as store]))

(def at "2026-08-11T08:36:17.156Z")

(deftest canonical-bytes-do-not-depend-on-map-order
  (testing "同じ値は同じバイト列。でなければ receipt の digest が 2 通りになる"
    (is (= (canonical/line {:b 2 :a 1 :c {:z 26 :y 25}})
           (canonical/line {:c {:y 25 :z 26} :a 1 :b 2}))))
  (is (str/ends-with? (canonical/line {:a 1}) "\n"))
  (is (= 2 (count (str/split-lines (canonical/lines [{:a 1} {:b 2}]))))))

(deftest raw-paths-are-content-addressed
  (let [p (store/raw-path {:sha256 "abcdef0123456789aaaa" :kind :page-text :ext "txt" :at at})]
    (is (= "raw/2026-08-11/abcdef012345-page-text.txt" p))
    (testing "同じ本文は同じパス —— 2 回読んでもオブジェクトは 1 つ"
      (is (= p (store/raw-path {:sha256 "abcdef0123456789aaaa" :kind :page-text :ext "txt"
                                :at "2026-08-11T23:59:59Z"}))))))

(deftest a-read-that-found-nothing-is-still-a-receipt
  (let [r (store/receipt {:at at :subject "http://x.test/" :kind :page-text
                          :url "http://x.test/" :status 404 :text-source {:kind :none}})]
    (is (= :corpus (:noren/kind r)))
    (is (= 404 (:http-status r)))
    (is (nil? (:raw r)) "読めなかったので raw は無い。**receipt は在る**")))

(deftest the-journal-never-carries-the-model-output
  (let [e (store/journal-entry {:prospect "x.test" :decision :commit
                                :raw-reply "{:business-name \"…\" 長い生返答}"
                                :message {:body "…"} :prescription {:claims []}}
                               at)]
    (is (nil? (:raw-reply e)) "生返答は raw 面。journal は sha256 で参照する")
    (is (nil? (:message e)))
    (is (nil? (:prescription e)))
    (is (= :decision (:noren/kind e)))
    (is (= at (:at e)))))

;; ── 検証 ─────────────────────────────────────────────────────────────────

(def receipts
  [{:raw {:path "raw/d/a.txt" :sha256 "aaa" :bytes 3}}
   {:raw {:path "raw/d/b.txt" :sha256 "bbb" :bytes 5}}
   {:http-status 404}])

(deftest verify-separates-identity-from-custody
  (testing "実体が receipt どおり"
    (let [res (store/verify receipts (fn [p] (case p
                                               "raw/d/a.txt" {:sha256 "aaa" :bytes 3}
                                               "raw/d/b.txt" {:sha256 "bbb" :bytes 5})))]
      (is (= 2 (:ok res)))
      (is (= 1 (:no-raw res)))
      (is (= :ok (store/verdict res)))))

  (testing "**annex から drop されているのは失敗ではない** —— drop できることが annex の理由"
    (let [res (store/verify receipts (fn [p] (if (= p "raw/d/a.txt") :absent {:sha256 "bbb" :bytes 5})))]
      (is (= 1 (count (:absent res))))
      (is (= :ok (store/verdict res)))))

  (testing "中身が変わっていたら壊れている"
    (let [res (store/verify receipts (fn [_] {:sha256 "zzz" :bytes 9}))]
      (is (= 2 (count (:mismatch res))))
      (is (= :broken (store/verdict res)))))

  (testing "ファイルごと消えていたら壊れている（drop とは別）"
    (let [res (store/verify receipts (constantly nil))]
      (is (= 2 (count (:missing res))))
      (is (= :broken (store/verdict res))))))
